#!/usr/bin/env python3
"""Extract exact barcode/photo matches from OFF's official bulk CSV export.

Streams the export rather than issuing thousands of product API requests. The
output is a small, reviewable metadata snapshot; it never changes nutrition.
"""
import argparse
import csv
import gzip
import io
import json
import sqlite3
import time
import urllib.request
from pathlib import Path

USER_AGENT = 'DietLog/0.7.11 (https://github.com/shadow-pheonix/Android_Development)'
EXPORT_URL = 'https://openfoodfacts-ds.s3.eu-west-3.amazonaws.com/en.openfoodfacts.org.products.csv.gz'


def extract(source, barcodes):
    csv.field_size_limit(32 * 1024 * 1024)
    reader = csv.DictReader(source, delimiter='\t')
    if not {'code', 'image_url'} <= set(reader.fieldnames or []):
        raise ValueError('OFF export is missing code/image_url columns')
    photos = {}
    started = time.monotonic()
    for index, row in enumerate(reader, 1):
        code = row.get('code', '')
        if code in barcodes:
            url = row.get('image_small_url') or row.get('image_url')
            if url:
                photos[code] = {
                    'url': url,
                    'source_url': 'https://world.openfoodfacts.org/product/' + code,
                    'credit': 'Open Food Facts contributors',
                    'license': 'CC BY-SA 3.0',
                    'license_url': 'https://creativecommons.org/licenses/by-sa/3.0/',
                    'kind': 'product',
                }
        if index % 250000 == 0:
            print(f'OFF export: {index:,} rows scanned, {len(photos):,} matching photos, '
                  f'{time.monotonic() - started:.0f}s', flush=True)
    return photos


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--food-db', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--export-url', default=EXPORT_URL)
    parser.add_argument('--export-file', help='Previously downloaded .csv.gz export (supports resumable downloads)')
    args = parser.parse_args()
    with sqlite3.connect(args.food_db) as db:
        barcodes = {row[0] for row in db.execute("SELECT barcode FROM foods WHERE source='OFF'")}
    request = urllib.request.Request(args.export_url, headers={'User-Agent': USER_AGENT})
    response = open(args.export_file, 'rb') if args.export_file else urllib.request.urlopen(request, timeout=60)
    with response:
        print('Streaming official OFF export; matching', len(barcodes), 'catalogue barcodes', flush=True)
        with gzip.GzipFile(fileobj=response) as compressed:
            with io.TextIOWrapper(compressed, encoding='utf-8', newline='') as source:
                photos = extract(source, barcodes)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.tmp')
    temporary.write_text(json.dumps({'source': args.export_url, 'photos': photos},
                                    ensure_ascii=False, sort_keys=True), encoding='utf-8')
    temporary.replace(output)
    print('OFF matching photos:', len(photos), flush=True)


if __name__ == '__main__':
    main()
