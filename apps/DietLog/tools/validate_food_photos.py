#!/usr/bin/env python3
"""Check the bundled photo manifest against the pinned catalogue and image bytes."""
import argparse
import hashlib
import json
import re
import sqlite3
from collections import Counter
from pathlib import Path
from PIL import Image

try:
    from .fetch_commons_photo_metadata import allowed_license
except ImportError:
    from fetch_commons_photo_metadata import allowed_license

def validate(db_path, assets, sources=None):
    assets = Path(assets)
    manifest = json.loads((assets / 'food_photos.json').read_text())
    if manifest['schema_version'] not in (1, 2):
        raise ValueError('Unsupported manifest schema')
    curated = manifest['schema_version'] == 2
    if curated:
        try:
            from .curated_food_photos import assignments
        except ImportError:
            from curated_food_photos import assignments
        sources = sources or Path(__file__).with_name('food_photo_sources.json')
        definitions = json.loads(Path(sources).read_text())['photos']
        with sqlite3.connect(db_path) as db:
            matching_rows = list(db.execute('SELECT code,name,brand,source FROM foods ORDER BY code'))
        if assignments(matching_rows, definitions) != manifest['foods']:
            raise ValueError('Photo assignments differ from reviewed matching rules')
        if set(manifest['photos']) != set(manifest['foods'].values()):
            raise ValueError('Unused or missing photo metadata')
        for key, photo in manifest['photos'].items():
            if photo.get('sha256') != definitions[key]['reviewed_sha256']:
                raise ValueError('Photo changed since visual review')
            for field in ('url', 'source_url', 'title', 'credit', 'license', 'license_url',
                          'kind', 'provider', 'presentation'):
                if photo.get(field) != definitions[key][field]:
                    raise ValueError('Photo differs from reviewed source: ' + field)
    with sqlite3.connect(db_path) as db:
        rows = {code: (barcode, source) for code, barcode, source in db.execute('SELECT code,barcode,source FROM foods')}
    coverage = Counter()
    for code, key in manifest['foods'].items():
        barcode, source = rows[code]
        photo = manifest['photos'][key]
        if curated:
            pass  # assignments() verifies official-first and representative fallback rules.
        elif source == 'OFF':
            if key != 'off:' + barcode or photo['kind'] != 'product':
                raise ValueError('Product photo does not match barcode')
        elif not key.startswith('commons:') or photo['kind'] != 'representative':
            raise ValueError('Unexpected representative photo assignment')
        coverage[source] += 1
    if dict(coverage) != manifest['coverage']:
        raise ValueError('Incorrect coverage totals')
    checked = set()
    for photo in manifest['photos'].values():
        asset = photo['asset']
        if not re.fullmatch(r'food_photos/[a-f0-9]{64}\.webp', asset):
            raise ValueError('Invalid asset path')
        for field in ['credit', 'license', 'license_url', 'source_url', 'transform']:
            if not photo.get(field):
                raise ValueError('Missing attribution: ' + field)
        if not curated and not allowed_license(photo['license'], photo['license_url']):
            raise ValueError('Unsupported photo license')
        if not photo['source_url'].startswith('https://') or not photo['license_url'].startswith('https://'):
            raise ValueError('Invalid attribution link')
        if asset in checked: continue
        checked.add(asset)
        raw = (assets / asset).read_bytes()
        if hashlib.sha256(raw).hexdigest() != photo['sha256'] or photo['sha256'] not in asset:
            raise ValueError('Photo hash mismatch')
        with Image.open(assets / asset) as image:
            image.load()
            if image.format != 'WEBP' or not (1 <= image.width <= 256 and 1 <= image.height <= 256):
                raise ValueError('Invalid thumbnail')
    if checked != {'food_photos/' + p.name for p in (assets / 'food_photos').glob('*.webp')}:
        raise ValueError('Unreferenced assets')
    print('Validated', len(checked), 'images for', len(manifest['foods']), 'foods:', dict(coverage))
    return manifest

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--food-db', required=True)
    parser.add_argument('--assets', required=True)
    parser.add_argument('--sources', help='Reviewed source registry (schema 2)')
    args = parser.parse_args()
    validate(args.food_db, args.assets, args.sources)
