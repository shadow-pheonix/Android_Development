"""Contracts for the bounded recipe catalogue and its pinned nutrition references.

These checks use the real planner asset, not a miniature replacement catalogue.
The database-dependent checks run when CI has built the pinned full food catalogue.
"""
import json
import math
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/planner_meals.json'
DATABASE = ROOT / 'app/src/main/assets/offline_foods.db'
NUTRITION_FIXTURE = ROOT / 'app/src/test/resources/planner_fixture_nutrition.json'
CORE_AND_OPTIONAL = (
    'calories_100g', 'protein_100g', 'carbs_100g', 'fat_100g',
    'fiber_100g', 'sodium_100g', 'sugars_100g', 'saturated_fat_100g',
)
LEGACY_IDS = {
    'oats_milk', 'oats_curd', 'pesarattu', 'egg_oats', 'moong_chilla',
    'tofu_scramble', 'curd_fruit', 'chana_salad', 'rice_moong', 'atta_moong',
    'rice_toor', 'atta_toor', 'khichdi', 'curd_rice', 'chicken_rice',
    'chicken_roti', 'tofu_rice', 'tofu_roti', 'egg_rice', 'egg_roti',
    'chana_rice', 'chana_roti', 'rice_dal_curd', 'banana_peanuts',
    'guava_curd', 'egg_fruit', 'milk_banana', 'chana_snack', 'tofu_salad',
    'curd_oats_lunch', 'chana_tofu_bowl',
}


def allowed(recipe, foods, meal, diet='ANY', can_cook=True, minutes=45):
    """Independent catalogue eligibility count; Java integration tests verify plans."""
    if not recipe['meal_mask'] & (1 << meal):
        return False
    if recipe['prep_minutes'] > minutes or recipe['requires_cooking'] and not can_cook:
        return False
    for item in recipe['ingredients']:
        food = foods[item['food']]
        tags = set(food['tags'])
        if diet != 'ANY' and not food['ingredients_known']:
            return False
        if diet != 'ANY' and tags & {'meat', 'fish', 'shellfish'}:
            return False
        if diet in {'VEGETARIAN', 'VEGAN'} and 'egg' in tags:
            return False
        if diet == 'VEGAN' and tags & {'dairy', 'honey'}:
            return False
    return True


class PlannerCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(ASSET.read_text())
        cls.foods = cls.data['foods']
        cls.recipes = cls.data['recipes']

    def test_catalogue_structure_bounds_and_stable_preferences(self):
        self.assertLessEqual(len(self.foods), 200)
        # Leaves room for 100 saved personal recipes inside the engine's 200 cap.
        self.assertLessEqual(len(self.recipes), 90)
        self.assertGreaterEqual(len(self.recipes), 60)
        self.assertIn('not live local prices', self.data['price_note'])
        self.assertIn('elapsed time', self.data['prep_note'])
        ids, names, fingerprints = set(), set(), set()
        covered = 0
        for key, food in self.foods.items():
            self.assertTrue(food['code'].startswith(('IFCT-', 'USDA-', 'INDB-')), key)
            self.assertIsInstance(food['ingredients_known'], bool, key)
            self.assertTrue(food['state'], key)
            self.assertTrue(food['tags'], key)
            self.assertEqual(len(food['tags']), len(set(food['tags'])), key)
            price = food['reference_price_per_100g']
            self.assertTrue(math.isfinite(price), key)
            self.assertGreaterEqual(price, 0, key)
        for recipe in self.recipes:
            rid = recipe['id']
            self.assertNotIn(rid, ids)
            ids.add(rid)
            self.assertNotIn(recipe['name'], names)
            names.add(recipe['name'])
            self.assertTrue(recipe['family'], rid)
            self.assertIn(recipe['primary_meal'], range(4), rid)
            self.assertTrue(recipe['meal_mask'] & (1 << recipe['primary_meal']), rid)
            self.assertIn(recipe['meal_mask'], (1, 2, 4, 8, 10), rid)
            covered |= recipe['meal_mask']
            self.assertGreater(recipe['yield_grams'], 0, rid)
            self.assertLessEqual(recipe['yield_grams'], 10000, rid)
            self.assertTrue(recipe['instructions'], rid)
            self.assertGreaterEqual(recipe['prep_minutes'], 0, rid)
            self.assertLessEqual(recipe['prep_minutes'], 45, rid)
            self.assertGreater(len(recipe['ingredients']), 0, rid)
            self.assertLessEqual(len(recipe['ingredients']), 20, rid)
            ingredients = []
            for item in recipe['ingredients']:
                self.assertIn(item['food'], self.foods, rid)
                self.assertGreater(item['grams'], 0, rid)
                self.assertLessEqual(item['grams'], 5000, rid)
                self.assertTrue(math.isfinite(item['grams']), rid)
                ingredients.append((self.foods[item['food']]['code'], item['grams']))
            self.assertEqual(len(ingredients), len({code for code, _ in ingredients}), rid)
            # Renaming exactly the same edible ingredients is not new variety.
            fingerprint = tuple(sorted(ingredients))
            self.assertNotIn(fingerprint, fingerprints, rid)
            fingerprints.add(fingerprint)
        self.assertEqual(15, covered)
        self.assertTrue({'meal:' + rid for rid in LEGACY_IDS} <= ids)

    def test_andhra_has_real_dishes_and_distinct_meal_groups(self):
        andhra = [r for r in self.recipes if r['cuisine'] == 'Andhra']
        self.assertGreaterEqual(len(andhra), 40)
        families = {r['family'] for r in andhra}
        self.assertTrue({
            'pesarattu', 'idli', 'dosa', 'upma', 'atukula_upma', 'garelu',
            'pulihora', 'sambar', 'charu', 'perugu_annam', 'gutti_vankaya',
            'bendakaya_vepudu', 'beerakaya_pappu', 'sorakaya_kura', 'punugulu',
            'mirchi_bajji', 'maramaralu',
        } <= families)
        for meal in range(4):
            primary = {r['family'] for r in andhra if r['primary_meal'] == meal}
            self.assertGreaterEqual(len(primary), 4, f'Andhra primary meal {meal}: {primary}')
            for diet in ('ANY', 'VEGETARIAN', 'EGGS_ALLOWED', 'VEGAN'):
                eligible = {
                    r['family'] for r in andhra if allowed(r, self.foods, meal, diet)
                }
                self.assertGreaterEqual(len(eligible), 5, f'{diet} Andhra meal {meal}: {eligible}')

    def test_no_cook_has_real_meals_and_five_distinct_families_per_slot(self):
        needs_heat = {
            'rice', 'moong', 'whole_moong', 'toor', 'urad', 'chana_dal',
            'atta', 'ragi', 'rava', 'chicken', 'fish', 'peas', 'potato',
        }
        for recipe in self.recipes:
            if recipe['requires_cooking']:
                continue
            self.assertFalse(needs_heat & {i['food'] for i in recipe['ingredients']}, recipe['id'])
            cooked = [self.foods[i['food']] for i in recipe['ingredients']
                      if 'cooked' in self.foods[i['food']]['state']]
            if cooked:
                self.assertTrue(
                    any(word in recipe['instructions'].lower()
                        for word in ('already-cooked', 'already cooked', 'fully cooked')),
                    recipe['id'],
                )
        for diet in ('ANY', 'VEGETARIAN', 'EGGS_ALLOWED', 'VEGAN'):
            for meal in range(4):
                eligible = {r['family'] for r in self.recipes
                            if allowed(r, self.foods, meal, diet, can_cook=False, minutes=10)}
                self.assertGreaterEqual(len(eligible), 5, f'{diet} no-cook meal {meal}: {eligible}')

    def test_unknown_cooked_composition_never_claims_dietary_suitability(self):
        unknown = [food for food in self.foods.values() if food['code'].startswith('INDB-')]
        self.assertGreaterEqual(len(unknown), 10)
        for food in unknown:
            self.assertFalse(food['ingredients_known'], food['code'])
            self.assertIn('no complete ingredient list', food['composition_note'])
            self.assertIn('cooked', food['state'])
        for recipe in self.recipes:
            if any(not self.foods[i['food']]['ingredients_known'] for i in recipe['ingredients']):
                self.assertIn('source does not provide a complete ingredient list', recipe['instructions'])
                for diet in ('VEGETARIAN', 'EGGS_ALLOWED', 'VEGAN'):
                    self.assertFalse(allowed(recipe, self.foods, recipe['primary_meal'], diet))

    def test_starch_swaps_and_purchased_versions_share_family(self):
        by_id = {r['id']: r for r in self.recipes}
        groups = (
            ('rice_moong', 'atta_moong', 'rice_toor', 'atta_toor', 'rice_dal_curd'),
            ('chicken_rice', 'chicken_roti', 'andhra_chicken', 'bought_chicken_rice'),
            ('tofu_rice', 'tofu_roti'), ('egg_rice', 'egg_roti', 'andhra_egg_roti'),
            ('idli_chutney', 'bought_idli'), ('dosa_sambar', 'vegetable_dosa', 'bought_dosa'),
            ('rava_upma', 'bought_upma'), ('garelu_chutney', 'bought_garelu'),
            ('curd_rice', 'ready_perugu_bowl', 'bought_perugu'),
        )
        for group in groups:
            self.assertEqual(1, len({by_id['meal:' + rid]['family'] for rid in group}), group)

    def test_reviewed_ingredient_preferences_are_symmetric_and_keep_nutrition_ids(self):
        # These two pairs are plain versions of the same ingredient. Split dal,
        # rice flakes, puffed rice and compound INDB dishes are not equivalent.
        reviewed = {
            frozenset(('IFCT-A015', 'USDA-168878')),
            frozenset(('IFCT-B011', 'USDA-174257')),
        }
        by_code = {food['code']: food for food in self.foods.values()}
        observed = set()
        for food in self.foods.values():
            if 'preference_ids' not in food:
                continue
            ids = food['preference_ids']
            self.assertIsInstance(ids, list, food['code'])
            self.assertEqual(len(ids), len(set(ids)), food['code'])
            self.assertIn(food['code'], ids)
            group = frozenset(ids)
            self.assertIn(group, reviewed, food['code'])
            observed.add(group)
            for code in ids:
                self.assertIn(code, by_code)
                self.assertTrue(by_code[code]['ingredients_known'], code)
                self.assertEqual(group, frozenset(by_code[code]['preference_ids']), code)
        self.assertEqual(reviewed, observed)
        self.assertEqual('IFCT-A015', self.foods['rice']['code'])
        self.assertEqual('USDA-168878', self.foods['cooked_rice']['code'])
        self.assertEqual('IFCT-B011', self.foods['whole_moong']['code'])
        self.assertEqual('USDA-174257', self.foods['cooked_moong']['code'])

    def test_preference_pairs_are_supported_by_pinned_source_foods(self):
        if not DATABASE.exists():
            self.skipTest('Full catalogue is generated before Android CI')
        with sqlite3.connect(f'file:{DATABASE}?mode=ro', uri=True) as db:
            sources = {
                code: db.execute('SELECT name,extra_json FROM foods WHERE code=?', (code,)).fetchone()
                for code in ('IFCT-A015', 'USDA-168878', 'IFCT-B011', 'USDA-174257')
            }
        self.assertEqual('Rice, raw, milled', sources['IFCT-A015'][0])
        self.assertEqual('Rice, white, long-grain, regular, enriched, cooked', sources['USDA-168878'][0])
        raw_moong = json.loads(sources['IFCT-B011'][1])['ifct']
        self.assertEqual('Green gram, whole', raw_moong['name'])
        self.assertEqual('Vigna radiata', raw_moong['scie'])
        self.assertIn('Mung bean', raw_moong['lang'])
        self.assertEqual('Mung beans, mature seeds, cooked, boiled, without salt', sources['USDA-174257'][0])

    def test_pinned_nutrition_references_when_catalogue_is_built(self):
        if not DATABASE.exists():
            self.skipTest('Full catalogue is generated before Android CI')
        with sqlite3.connect(f'file:{DATABASE}?mode=ro', uri=True) as db:
            for food in self.foods.values():
                row = db.execute(
                    'SELECT ' + ','.join(CORE_AND_OPTIONAL) + ' FROM foods WHERE code=?',
                    (food['code'],),
                ).fetchone()
                self.assertIsNotNone(row, food['code'])
                self.assertTrue(all(v is not None and math.isfinite(v) and v >= 0 for v in row[:4]), food['code'])
                self.assertTrue(all(v is None or math.isfinite(v) and v >= 0 for v in row[4:]), food['code'])

    def test_java_nutrition_fixture_matches_exact_full_database(self):
        if not DATABASE.exists():
            self.skipTest('Full catalogue is generated before Android CI')
        fixture = json.loads(NUTRITION_FIXTURE.read_text())
        self.assertEqual({food['code'] for food in self.foods.values()}, set(fixture))
        with sqlite3.connect(f'file:{DATABASE}?mode=ro', uri=True) as db:
            for code, values in fixture.items():
                self.assertEqual(8, len(values), code)
                actual = db.execute(
                    'SELECT ' + ','.join(CORE_AND_OPTIONAL) + ' FROM foods WHERE code=?', (code,)
                ).fetchone()
                self.assertIsNotNone(actual, code)
                for expected, value in zip(values, actual):
                    if value is None:
                        self.assertIsNone(expected, code)
                    else:
                        self.assertTrue(math.isfinite(expected), code)
                        self.assertAlmostEqual(value, expected, delta=1e-9, msg=code)


if __name__ == '__main__':
    unittest.main()
