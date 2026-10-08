import io
import unittest
from PIL import Image
from tools.build_food_photos import match_group, safe_url, compress_photo, check_photo_quality
from tools.fetch_off_photo_metadata import extract
from tools.fetch_commons_photo_metadata import allowed_license

class PhotoTests(unittest.TestCase):
    def test_exact_barcode_only(self):
        rows = io.StringIO('code\timage_url\timage_small_url\n00123\thttps://x/large\thttps://x/small\n123\thttps://x/other\t\n')
        result = extract(rows, {'00123'})
        self.assertEqual(set(result), {'00123'})
        self.assertEqual(result['00123']['url'], 'https://x/small')

    def test_longest_whole_phrase_and_source(self):
        groups = [dict(id='rice', aliases=['rice'], sources=['USDA']),
                  dict(id='fried', aliases=['fried rice'], sources=['USDA'])]
        self.assertEqual(match_group('Rice, fried rice with vegetables', 'USDA', groups), 'fried')
        self.assertIsNone(match_group('Licorice candy', 'USDA', groups))
        self.assertIsNone(match_group('fried rice', 'OFF', groups))
        self.assertIsNone(match_group('fried rice', 'IFCT', groups))

    def test_blank_photo_rejected(self):
        raw = io.BytesIO()
        Image.new('RGB', (100, 100), 'black').save(raw, format='WEBP')
        with self.assertRaisesRegex(ValueError, 'blank'):
            check_photo_quality(raw.getvalue())

    def test_download_hosts_and_licenses(self):
        self.assertTrue(safe_url('https://images.openfoodfacts.org/a.jpg'))
        for url in ['http://images.openfoodfacts.org/a', 'https://images.openfoodfacts.org.evil/a',
                    'https://user@images.openfoodfacts.org/a', 'file:///tmp/a']:
            self.assertFalse(safe_url(url))
        self.assertTrue(allowed_license('CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/'))
        self.assertFalse(allowed_license('CC BY-NC 4.0', 'https://creativecommons.org/licenses/by-nc/4.0/'))

    def test_compression_is_bounded_and_opaque(self):
        raw = io.BytesIO()
        Image.new('RGBA', (1200, 600), (0, 0, 0, 0)).save(raw, format='PNG')
        with Image.open(io.BytesIO(compress_photo(raw.getvalue()))) as photo:
            self.assertEqual(photo.format, 'WEBP')
            self.assertEqual(photo.size, (256, 128))
            self.assertTrue(all(channel >= 250 for channel in photo.getpixel((0, 0))[:3]))
        with self.assertRaises(ValueError):
            raw = io.BytesIO()
            Image.new('RGB', (8, 8)).save(raw, format='PNG')
            compress_photo(raw.getvalue())

class ManifestTests(unittest.TestCase):
    def test_assets_and_exact_product_assignment(self):
        import hashlib
        import json
        import sqlite3
        import tempfile
        from pathlib import Path
        from tools.validate_food_photos import validate
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / 'foods.db'
            with sqlite3.connect(database) as db:
                db.execute('CREATE TABLE foods(code TEXT,barcode TEXT,source TEXT)')
                db.execute("INSERT INTO foods VALUES('OFF:00123','00123','OFF')")
            raw = io.BytesIO()
            Image.new('RGB', (80, 80), 'white').save(raw, format='WEBP')
            digest = hashlib.sha256(raw.getvalue()).hexdigest()
            (root / 'food_photos').mkdir()
            asset = 'food_photos/' + digest + '.webp'
            (root / asset).write_bytes(raw.getvalue())
            photo = dict(asset=asset, sha256=digest, credit='Contributor', license='CC BY-SA 3.0',
                         license_url='https://creativecommons.org/licenses/by-sa/3.0/',
                         source_url='https://world.openfoodfacts.org/product/00123',
                         transform='Resized', kind='product')
            manifest = dict(schema_version=1, photos={'off:00123': photo},
                            foods={'OFF:00123': 'off:00123'}, coverage={'OFF': 1})
            path = root / 'food_photos.json'
            path.write_text(json.dumps(manifest))
            validate(database, root)
            manifest['photos']['off:123'] = manifest['photos'].pop('off:00123')
            manifest['foods']['OFF:00123'] = 'off:123'
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'barcode'):
                validate(database, root)
            manifest['photos']['off:00123'] = manifest['photos'].pop('off:123')
            manifest['foods']['OFF:00123'] = 'off:00123'
            path.write_text(json.dumps(manifest))
            (root / asset).write_bytes(b'corrupt image')
            with self.assertRaisesRegex(ValueError, 'hash'):
                validate(database, root)

# Keep the curated matching regressions in the app's existing CI test entry point.
from tools.test_curated_photos import CuratedPhotoTests
