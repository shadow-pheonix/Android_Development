import copy
import hashlib
import io
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image
from tools.curated_food_photos import match_photo, validate_definition, acquire, build
from tools.validate_food_photos import validate


def stock(aliases):
    return dict(provider='stock', kind='representative', presentation='food',
                sources=['INDB', 'USDA'], aliases=aliases, exclude_aliases=['powder', 'mix', 'raw'],
                url='https://images.pexels.com/photos/20422128/a.jpeg',
                source_url='https://www.pexels.com/photo/meal-and-soup-20422128/',
                license_url='https://www.pexels.com/license/',
                approved_hosts=['images.pexels.com', 'www.pexels.com'],
                title='Idli', credit='Photographer / Pexels', license='Pexels',
                reviewed=True, review_note='Studio food photograph', reviewed_sha256='a' * 64)


class CuratedPhotoTests(unittest.TestCase):
    def setUp(self):
        self.official = dict(provider='official_brand', kind='product', presentation='packshot',
                             sources=['OFF'], brands=['Yoga Bar', 'Yogabar'], food_codes=['OFF-00123'])
        self.fallback = dict(kind='representative', presentation='packshot', sources=['OFF'],
                             aliases=['muesli'], exclude_aliases=['bar'])

    def test_official_wins_over_generic_fallback(self):
        definitions = {'official': self.official, 'fallback': self.fallback}
        self.assertEqual(match_photo(('OFF-00123', 'Chocolate muesli', 'Yogabar', 'OFF'), definitions), 'official')
        self.assertEqual(match_photo(('OFF-09999', 'Chocolate muesli', 'Other brand', 'OFF'), definitions), 'fallback')

    def test_brand_and_product_must_both_be_approved(self):
        definitions = {'official': self.official}
        for row in [('OFF-00123', 'Muesli', 'Other brand', 'OFF'),
                    ('OFF-00123', 'Muesli', 'Yogabarn', 'OFF'),
                    ('OFF-00124', 'Other flavour muesli', 'Yoga Bar', 'OFF'),
                    ('OFF-00123', 'Muesli', None, 'OFF'),
                    ('INDB-00123', 'Muesli', 'Yoga Bar', 'INDB')]:
            self.assertIsNone(match_photo(row, definitions))

    def test_longest_family_and_exclusions_prevent_bad_fallbacks(self):
        definitions = {'generic': self.fallback,
                       'specific': dict(self.fallback, aliases=['chocolate muesli'])}
        self.assertEqual(match_photo(('OFF-9', 'Dark chocolate muesli', 'Other', 'OFF'), definitions), 'specific')
        self.assertIsNone(match_photo(('OFF-9', 'Muesli bar', 'Other', 'OFF'), definitions))
        self.assertIsNone(match_photo(('OFF-9', 'Amueslified dessert', 'Other', 'OFF'), definitions))

    def test_cooked_stock_does_not_match_raw_or_packaged_mix(self):
        definitions = {'idli': stock(['idli'])}
        self.assertEqual(match_photo(('INDB-1', 'Rice idli', None, 'INDB'), definitions), 'idli')
        for name, source in [('Idli mix', 'USDA'), ('Raw idli flour', 'USDA'), ('Idli', 'OFF')]:
            self.assertIsNone(match_photo(('1', name, None, source), definitions))

    def test_community_or_unreviewed_sources_rejected(self):
        entry = stock(['idli'])
        validate_definition('stock:idli', entry)
        for changes in [dict(provider='off'), dict(reviewed=False),
                        dict(url='https://images.openfoodfacts.org/a.jpg'),
                        dict(url='https://user@images.pexels.com/a.jpg'),
                        dict(kind='product')]:
            bad = copy.deepcopy(entry);bad.update(changes)
            with self.assertRaises(ValueError):validate_definition('stock:idli', bad)

    def test_ambiguous_official_assignments_fail(self):
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            match_photo(('OFF-00123', 'Muesli', 'Yoga Bar', 'OFF'),
                        {'a': self.official, 'b': self.official})

    def test_reference_food_head_is_not_an_ingredient_or_colour(self):
        definitions = {'carrot': stock(['carrot']), 'orange': stock(['orange']),
                       'salt': stock(['salt']), 'peas': stock(['peas'])}
        self.assertEqual(match_photo(('1', 'Carrot, orange', None, 'USDA'), definitions), 'carrot')
        self.assertEqual(match_photo(('2', 'Peas, cooked, with salt', None, 'USDA'), definitions), 'peas')

    def test_current_registry_food_type_regressions(self):
        definitions = json.loads(Path(__file__).with_name('food_photo_sources.json').read_text())['photos']
        rows = [
            (('OFF-9', 'Biryani basmati rice', 'Aliza', 'OFF'), 'stock:rice'),
            (('OFF-9', 'Dark chocolate muesli', 'Other', 'OFF'), 'fallback:muesli'),
            (('OFF-9', 'Whey Protein Bar Chocolate Fudge', 'Other', 'OFF'), 'fallback:protein-bars'),
            (('OFF-9', 'Chocolate herbal tea', 'Other', 'OFF'), 'stock:tea'),
            (('OFF-9', 'Dark chocolate peanut butter', 'Other', 'OFF'), 'stock:peanut-butter'),
            (('INDB-1', 'Bread pakora', None, 'INDB'), 'stock:pakora'),
            (('OFF-8904132946330', 'Upma', 'Masti Oye!', 'OFF'), 'fallback:upma'),
            (('OFF-9', 'BB cream', 'Sugar', 'OFF'), None),
            (('USDA-1', 'Pork, loin, leg cap steak, cooked, broiled', None, 'USDA'), 'stock:pork'),
            (('USDA-1', 'Beef, tenderloin, raw', None, 'USDA'), 'stock:beef'),
            (('USDA-1', 'Beef, tenderloin, cooked, roasted', None, 'USDA'), 'stock:cooked-beef'),
        ]
        for row, expected in rows:
            with self.subTest(name=row[1]):
                self.assertEqual(match_photo(row, definitions), expected)

    def test_registry_cannot_approve_community_host_or_skip_review_hash(self):
        entry = stock(['idli'])
        entry.update(provider='official_brand', url='https://images.openfoodfacts.org/a.jpg',
                     source_url='https://world.openfoodfacts.org/product/1',
                     approved_hosts=['images.openfoodfacts.org', 'world.openfoodfacts.org', 'www.pexels.com'])
        with self.assertRaisesRegex(ValueError, 'manufacturer'):
            validate_definition('bad', entry)
        entry = stock(['idli']);entry.pop('reviewed_sha256')
        with self.assertRaisesRegex(ValueError, 'reviewed thumbnail'):
            validate_definition('bad', entry)

    def test_failed_acquisition_preserves_previous_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = root / 'assets';(assets / 'food_photos').mkdir(parents=True)
            manifest = assets / 'food_photos.json';manifest.write_text('previous manifest')
            old = assets / 'food_photos' / 'previous.webp';old.write_bytes(b'previous photo')
            database = root / 'foods.db'
            with sqlite3.connect(database) as db:
                db.execute('CREATE TABLE foods(code TEXT,name TEXT,brand TEXT,source TEXT)')
                db.execute("INSERT INTO foods VALUES('1','Idli',NULL,'INDB')")
            sources = root / 'sources.json';sources.write_text(json.dumps({'photos': {'idli': stock(['idli'])}}))
            with patch('tools.curated_food_photos.acquire', side_effect=ValueError('changed image')):
                with self.assertRaisesRegex(ValueError, 'changed image'):
                    build(database, sources, root / 'cache', assets)
            self.assertEqual(manifest.read_text(), 'previous manifest')
            self.assertEqual(list((assets / 'food_photos').iterdir()), [old])
            self.assertEqual(old.read_bytes(), b'previous photo')

    def test_reviewed_bytes_and_schema_two_manifest_are_pinned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);cache = root / 'cache';cache.mkdir()
            entry = stock(['idli'])
            raw = io.BytesIO()
            image = Image.new('RGB', (80, 80), 'white')
            image.paste('orange', (20, 20, 60, 60));image.save(raw, format='WEBP')
            entry['reviewed_sha256'] = hashlib.sha256(raw.getvalue()).hexdigest()
            cached = cache / (hashlib.sha256(entry['url'].encode()).hexdigest() + '.webp')
            cached.write_bytes(raw.getvalue())
            assets = root / 'assets';(assets / 'food_photos').mkdir(parents=True)
            bad = dict(entry, reviewed_sha256='a' * 64)
            with self.assertRaisesRegex(ValueError, 'changed since visual review'):
                acquire(bad, cache, assets)
            database = root / 'foods.db'
            with sqlite3.connect(database) as db:
                db.execute('CREATE TABLE foods(code TEXT,barcode TEXT,name TEXT,brand TEXT,source TEXT)')
                db.execute("INSERT INTO foods VALUES('1',NULL,'Idli',NULL,'INDB')")
            sources = root / 'sources.json';sources.write_text(json.dumps({'photos': {'idli': entry}}))
            manifest = build(database, sources, cache, assets)
            validate(database, assets, sources)
            manifest['photos']['idli']['provider'] = 'community'
            (assets / 'food_photos.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'reviewed source'):
                validate(database, assets, sources)
