import copy
import unittest
from tools.curated_food_photos import match_photo, validate_definition


def stock(aliases):
    return dict(provider='stock', kind='representative', presentation='food',
                sources=['INDB', 'USDA'], aliases=aliases, exclude_aliases=['powder', 'mix', 'raw'],
                url='https://images.pexels.com/photos/20422128/a.jpeg',
                source_url='https://www.pexels.com/photo/meal-and-soup-20422128/',
                license_url='https://www.pexels.com/license/',
                approved_hosts=['images.pexels.com', 'www.pexels.com'],
                title='Idli', credit='Photographer / Pexels', license='Pexels',
                reviewed=True, review_note='Studio food photograph')


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
