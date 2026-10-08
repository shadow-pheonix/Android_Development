"""Reviewed official packshots and stock photos; no community photographs.

Matching order: approved brand/product codes, then longest product-type phrase.
Fallbacks are always labelled representative and never replace an official match.
"""
import argparse
import concurrent.futures
import functools
import hashlib
import json
import re
import shutil
import sqlite3
import tempfile
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
try:
    from .build_food_photos import normalized, compress_photo, check_photo_quality
except ImportError:
    from build_food_photos import normalized, compress_photo, check_photo_quality

normalized = functools.lru_cache(maxsize=65536)(normalized)

# Manufacturer pages reviewed for this snapshot. The registry cannot authorize
# arbitrary image hosts (especially community uploads) just by declaring them.
BRAND_PAGES = frozenset(('britannia-international.com', 'epigamiastore.com',
    'happilo.com', 'nakpro.com', 'shop.orklaindia.com', 'true-elements.com',
    'www.cadbury.co.uk', 'www.englishoven.com', 'www.farmley.com',
    'www.idfreshfood.com', 'www.maggi.in', 'www.milkymist.com', 'www.yogabars.in'))
BRAND_IMAGES = frozenset(('britannia-international.com', 'cdn.shopify.com',
    'englishoven.com', 'shop.orklaindia.com', 'static.wixstatic.com',
    'tastehub.mdlzapps.cloud', 'www.englishoven.com', 'www.idfreshfood.com', 'www.maggi.in'))


def phrase(value, alias):
    alias = normalized(alias)
    return bool(alias) and ' ' + alias + ' ' in ' ' + normalized(value) + ' '


def match_photo(row, definitions):
    code, name, brand, source = row
    # Reference catalogues put the food first and preparation/ingredients after
    # commas. "Carrot, orange" is a carrot; "Peas, cooked, with salt" isn't salt.
    family_name = name.split(',')[0] if source in ('IFCT', 'USDA') else name
    exact, representative = [], []
    for key, entry in definitions.items():
        if source not in entry['sources']:
            continue
        if entry['kind'] == 'product':
            if source == 'OFF' and code in entry['food_codes'] and any(phrase(brand, a) for a in entry['brands']):
                exact.append(key)
            continue
        if source == 'OFF' and code in entry.get('fallback_codes', []):
            representative.append(((entry.get('priority', 0), 0, 0, True), key))
            continue
        aliases = [alias for alias in entry['aliases'] if phrase(family_name, alias)]
        if not aliases:
            continue
        if any(phrase(name, a) for a in entry.get('exclude_aliases', [])):
            continue
        if entry.get('require_any') and not any(phrase(name, a) for a in entry['require_any']):
            continue
        for alias in aliases:
            representative.append(((entry.get('priority', 0), len(normalized(alias).split()), len(normalized(alias)),
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
    else:
        if urllib.parse.urlsplit(entry['source_url']).hostname not in BRAND_PAGES or \
                urllib.parse.urlsplit(entry['license_url']).hostname not in BRAND_PAGES or \
                urllib.parse.urlsplit(entry['url']).hostname not in BRAND_IMAGES:
            raise ValueError('Unapproved manufacturer source')
    if entry['kind'] == 'product':
        if entry['provider'] != 'official_brand' or entry['sources'] != ['OFF'] or not entry['brands'] or not entry['food_codes']:
            raise ValueError('Official product requires approved brand and codes')
    elif not entry['aliases']:
        raise ValueError('Representative photo requires product-type aliases')
    if entry['kind'] == 'product' and entry['presentation'] != 'packshot':
        raise ValueError('Official product image must be a packshot')
    if not entry.get('reviewed') or not entry.get('review_note'):
        raise ValueError('Photo needs visual review')
    for field in ('title', 'credit', 'license'):
        if not entry.get(field):
            raise ValueError('Missing source metadata')
    if not set(entry['sources']) <= {'OFF', 'IFCT', 'INDB', 'USDA'}:
        raise ValueError('Unknown catalogue source')
    if not re.fullmatch(r'[a-f0-9]{64}', entry.get('reviewed_sha256', '')):
        raise ValueError('Missing reviewed thumbnail hash')


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
    if digest != entry['reviewed_sha256']:
        raise ValueError('Image changed since visual review: ' + entry['title'])
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
    photos = {}
    wanted = sorted(set(foods.values()))
    # Download and verify everything before touching the committed bundle. A
    # failed source must leave the previous assets and manifest intact.
    with tempfile.TemporaryDirectory(dir=cache, prefix='staging-') as directory:
        stage = Path(directory)
        (stage / 'food_photos').mkdir()
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
            futures = {executor.submit(acquire, definitions[key], cache, stage): key for key in wanted}
            for future in concurrent.futures.as_completed(futures):
                photos[futures[future]] = future.result()
        (assets / 'food_photos').mkdir(parents=True, exist_ok=True)
        for file in (stage / 'food_photos').glob('*.webp'):
            shutil.copyfile(file, assets / 'food_photos' / file.name)
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
