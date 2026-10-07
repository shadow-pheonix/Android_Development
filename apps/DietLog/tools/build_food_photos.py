#!/usr/bin/env python3
"""Download, validate, deduplicate and bundle attributed offline food thumbnails.

OFF products require exact barcode matches. Commons photos are representative
images selected by reviewed food-family aliases, never assigned to OFF products.
Cached downloads make interrupted acquisition resumable. No runtime network is
needed by the APK. Requires Pillow for image decoding and WebP compression.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import re
import sqlite3
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

from PIL import Image, ImageOps

USER_AGENT = 'DietLog/0.7.11 (https://github.com/shadow-pheonix/Android_Development)'
ALLOWED_HOSTS = {'images.openfoodfacts.org', 'openfoodfacts-images.s3.eu-west-3.amazonaws.com',
                 'upload.wikimedia.org', 'thumb.wikimedia.org'}
Image.MAX_IMAGE_PIXELS = 16_000_000


def normalized(value):
    value = unicodedata.normalize('NFKD', value or '').encode('ascii', 'ignore').decode().lower()
    return re.sub('[^a-z0-9]+', ' ', value).strip()


def match_group(name, source, groups):
    if source == 'OFF':
        return None
    name = ' ' + normalized(name) + ' '
    best = None
    best_score = None
    for group in groups:
        if source not in group['sources']:
            continue
        for alias in group['aliases']:
            alias = normalized(alias)
            position = name.find(' ' + alias + ' ')
            if position < 0:
                continue
            score = (len(alias.split()), len(alias), -position)
            if best_score is None or score > best_score:
                best_score, best = score, group['id']
    return best


def safe_url(url):
    parsed = urllib.parse.urlsplit(url)
    return parsed.scheme == 'https' and parsed.hostname in ALLOWED_HOSTS and not parsed.username and not parsed.password


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        if not safe_url(new_url):
            raise ValueError('Photo redirect is outside approved image hosts')
        return super().redirect_request(request, fp, code, message, headers, new_url)


def compress_photo(raw):
    with Image.open(io.BytesIO(raw)) as image:
        image = ImageOps.exif_transpose(image)
        if image.width < 32 or image.height < 32:
            raise ValueError('Photo is too small')
        image.thumbnail((256, 256), Image.Resampling.LANCZOS)
        rgba = image.convert('RGBA')
        background = Image.new('RGBA', rgba.size, 'white')
        background.alpha_composite(rgba)
        out = io.BytesIO()
        background.convert('RGB').save(out, format='WEBP', quality=68, method=6)
        return out.getvalue()


def acquire(metadata, cache, assets):
    metadata = dict(metadata)
    for field in ('source_url', 'license_url'):
        metadata[field] = metadata[field].replace('http://', 'https://', 1)
    url = metadata['url']
    if not safe_url(url):
        return None, 'unapproved-host'
    key = hashlib.sha256(url.encode()).hexdigest()
    cached = cache / (key + '.webp')
    try:
        if cached.is_file():
            encoded = cached.read_bytes()
        else:
            opener = urllib.request.build_opener(SafeRedirect())
            for attempt in range(3):
                try:
                    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
                    with opener.open(request, timeout=30) as response:
                        raw = response.read(8 * 1024 * 1024 + 1)
                    if len(raw) > 8 * 1024 * 1024:
                        raise ValueError('Photo exceeds download limit')
                    encoded = compress_photo(raw)
                    break
                except urllib.error.HTTPError as error:
                    if error.code not in (408, 429, 500, 502, 503, 504) or attempt == 2:
                        raise
                    time.sleep(30 if error.code == 429 else 2 ** (attempt + 1))
            cached.write_bytes(encoded)
        digest = hashlib.sha256(encoded).hexdigest()
        path = assets / 'food_photos' / (digest + '.webp')
        if not path.is_file():
            path.write_bytes(encoded)
        return {**metadata, 'asset': 'food_photos/' + path.name, 'sha256': digest,
                'transform': 'Resized to at most 256 px, metadata removed, compressed as WebP'}, None
    except Exception as error:
        return None, type(error).__name__ + (':' + str(error.code) if isinstance(error, urllib.error.HTTPError) else '')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--food-db', required=True)
    parser.add_argument('--off')
    parser.add_argument('--commons', required=True)
    parser.add_argument('--queries', required=True)
    parser.add_argument('--exclude', help='JSON list of photo group IDs rejected during visual review')
    parser.add_argument('--cache', required=True)
    parser.add_argument('--assets', required=True)
    parser.add_argument('--workers', type=int, default=4, choices=range(1, 17))
    args = parser.parse_args()
    cache, assets = Path(args.cache), Path(args.assets)
    cache.mkdir(parents=True, exist_ok=True)
    (assets / 'food_photos').mkdir(parents=True, exist_ok=True)
    excluded = set(json.loads(Path(args.exclude).read_text())) if args.exclude else set()
    groups = [g for g in json.loads(Path(args.queries).read_text())['groups'] if g['id'] not in excluded and g.get('enabled', True)]
    commons = json.loads(Path(args.commons).read_text())['photos']
    off = json.loads(Path(args.off).read_text())['photos'] if args.off else {}
    candidates = {'commons:' + key: value for key, value in commons.items() if key not in excluded}
    candidates.update({'off:' + key: value for key, value in off.items()})
    food_candidates = {}
    with sqlite3.connect(args.food_db) as db:
        rows = list(db.execute('SELECT code,barcode,name,source FROM foods ORDER BY code'))
    for code, barcode, name, source in rows:
        if source == 'OFF':
            key = 'off:' + (barcode or '')
        else:
            group = match_group(name, source, groups)
            key = 'commons:' + group if group else None
        if key in candidates:
            food_candidates[code] = key
    # Keep all available Commons groups for visual review; only successful matches reach the DB.
    wanted = set(food_candidates.values()) | {key for key in candidates if key.startswith('commons:')}
    photos, errors = {}, {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(acquire, candidates[key], cache, assets): key for key in sorted(wanted)}
        for count, future in enumerate(concurrent.futures.as_completed(futures), 1):
            key = futures[future]
            photo, error = future.result()
            if photo:
                photos[key] = photo
            else:
                errors[key] = error
            if count % 100 == 0 or count == len(futures):
                print(f'Photos acquired {count}/{len(futures)}; usable {len(photos)}; failed {len(errors)}', flush=True)
    foods = {code: key for code, key in food_candidates.items() if key in photos}
    source_counts = Counter(source for code, _, _, source in rows if code in foods)
    manifest = {'schema_version': 1, 'photos': photos, 'foods': foods,
                'coverage': dict(source_counts), 'failures': errors}
    temporary = assets / 'food_photos.tmp'
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(',', ':')), encoding='utf-8')
    temporary.replace(assets / 'food_photos.json')
    used_assets = {photo['asset'] for photo in photos.values()}
    for file in (assets / 'food_photos').glob('*.webp'):
        if 'food_photos/' + file.name not in used_assets:
            file.unlink()
    print('Photo coverage', dict(source_counts), 'total', len(foods), flush=True)
    print('Bundled image bytes', sum(file.stat().st_size for file in (assets / 'food_photos').glob('*.webp')), flush=True)


if __name__ == '__main__':
    main()
