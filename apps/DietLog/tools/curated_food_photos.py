"""Reviewed official packshots and stock photos; no community photographs.

Matching order: approved brand/product codes, then longest product-type phrase.
Fallbacks are always labelled representative and never replace an official match.
"""
import argparse
import concurrent.futures
import hashlib
import json
import sqlite3
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
try:
    from .build_food_photos import normalized, compress_photo, check_photo_quality
except ImportError:
    from build_food_photos import normalized, compress_photo, check_photo_quality


def phrase(value, alias):
    alias = normalized(alias)
    return bool(alias) and ' ' + alias + ' ' in ' ' + normalized(value) + ' '


def match_photo(row, definitions):
    code, name, brand, source = row
    exact, representative = [], []
    for key, entry in definitions.items():
        if source not in entry['sources']:
            continue
        if entry['kind'] == 'product':
            if source == 'OFF' and code in entry['food_codes'] and any(phrase(brand, a) for a in entry['brands']):
                exact.append(key)
            continue
        if any(phrase(name, a) for a in entry.get('exclude_aliases', [])):
            continue
        for alias in entry['aliases']:
            if phrase(name, alias):
                representative.append(((len(normalized(alias).split()), len(normalized(alias)),
                                        entry['presentation'] == 'packshot'), key))
    if len(exact) > 1:
        raise ValueError('Ambiguous official product: ' + code)
    if exact:
        return exact[0]
    return max(representative)[1] if representative else None


def validate_definition(key, entry):
    if entry['provider'] not in ('official_brand', 'stock'):
        raise ValueError('Unapproved photo provider')
    if entry['kind'] not in ('product', 'representative') or entry['presentation'] not in ('packshot', 'food'):
        raise ValueError('Invalid photo classification')
    for field in ('url', 'source_url', 'license_url'):
        url = urllib.parse.urlsplit(entry[field])
        if url.scheme != 'https' or not url.hostname or url.username or url.password:
            raise ValueError('Invalid source URL')
        if url.hostname not in entry['approved_hosts']:
            raise ValueError('Source outside reviewed hosts')
    if entry['provider'] == 'stock':
        if entry['presentation'] != 'food' or entry['kind'] != 'representative':
            raise ValueError('Stock photo must be representative')
        if urllib.parse.urlsplit(entry['url']).hostname != 'images.pexels.com' or \
                urllib.parse.urlsplit(entry['source_url']).hostname != 'www.pexels.com' or \
                entry['license_url'] != 'https://www.pexels.com/license/':
            raise ValueError('Unapproved stock source')
    if entry['kind'] == 'product':
        if entry['provider'] != 'official_brand' or entry['sources'] != ['OFF'] or not entry['brands'] or not entry['food_codes']:
            raise ValueError('Official product requires approved brand and codes')
    elif not entry['aliases']:
        raise ValueError('Representative photo requires product-type aliases')
    if entry['provider'] == 'official_brand' and entry['presentation'] != 'packshot':
        raise ValueError('Official image must be a packshot')
    if not entry.get('reviewed') or not entry.get('review_note'):
        raise ValueError('Photo needs visual review')
    for field in ('title', 'credit', 'license'):
        if not entry.get(field):
            raise ValueError('Missing source metadata')
    if not set(entry['sources']) <= {'OFF', 'IFCT', 'INDB', 'USDA'}:
        raise ValueError('Unknown catalogue source')


def assignments(rows, definitions):
    for key, entry in definitions.items():
        validate_definition(key, entry)
    result = {}
    for row in rows:
        key = match_photo(row, definitions)
        if key:
            result[row[0]] = key
    return result


def acquire(entry, cache, assets):
    url = entry['url']
    cached = cache / (hashlib.sha256(url.encode()).hexdigest() + '.webp')
    if cached.exists():
        encoded = cached.read_bytes()
    else:
        hosts = set(entry['approved_hosts'])
        class ReviewedRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, message, headers, new_url):
                parsed = urllib.parse.urlsplit(new_url)
                if parsed.scheme != 'https' or parsed.hostname not in hosts or parsed.username or parsed.password:
                    raise ValueError('Redirect outside reviewed hosts')
                return super().redirect_request(request, fp, code, message, headers, new_url)
        request = urllib.request.Request(url, headers={'User-Agent': 'DietLog curated image acquisition'})
        with urllib.request.build_opener(ReviewedRedirect()).open(request, timeout=30) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError('Image exceeds download limit')
        encoded = compress_photo(raw)
        cached.write_bytes(encoded)
    check_photo_quality(encoded)
    digest = hashlib.sha256(encoded).hexdigest()
    asset = 'food_photos/' + digest + '.webp'
    (assets / asset).write_bytes(encoded)
    fields = ('url', 'source_url', 'title', 'credit', 'license', 'license_url', 'kind', 'provider', 'presentation')
    return {**{field: entry[field] for field in fields}, 'asset': asset, 'sha256': digest,
            'transform': 'Resized to at most 256 px, metadata removed, compressed as WebP'}


def build(db_path, sources, cache, assets, workers=4):
    definitions = json.loads(Path(sources).read_text())['photos']
    with sqlite3.connect(db_path) as db:
        rows = list(db.execute('SELECT code,name,brand,source FROM foods ORDER BY code'))
    foods = assignments(rows, definitions)
    cache, assets = Path(cache), Path(assets)
    cache.mkdir(parents=True, exist_ok=True)
    (assets / 'food_photos').mkdir(parents=True, exist_ok=True)
    # Fail before replacing the manifest if any reviewed source cannot be obtained.
    photos = {}
    wanted = sorted(set(foods.values()))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(acquire, definitions[key], cache, assets): key for key in wanted}
        for future in concurrent.futures.as_completed(futures):
            photos[futures[future]] = future.result()
    coverage = dict(Counter(source for code, _, _, source in rows if code in foods))
    manifest = dict(schema_version=2, photos=photos, foods=foods, coverage=coverage)
    temporary = assets / 'food_photos.tmp'
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(',', ':')))
    temporary.replace(assets / 'food_photos.json')
    used = {p['asset'] for p in photos.values()}
    for file in (assets / 'food_photos').glob('*.webp'):
        if 'food_photos/' + file.name not in used:
            file.unlink()
    print('Curated photos:', len(photos), 'Coverage:', len(foods), coverage)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('food-db', 'sources', 'cache', 'assets'):
        parser.add_argument('--' + option, required=True)
    parser.add_argument('--workers', type=int, default=4, choices=range(1, 9))
    args = parser.parse_args()
    build(args.food_db, args.sources, args.cache, args.assets, args.workers)
