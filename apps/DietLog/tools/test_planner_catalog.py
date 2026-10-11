"""Validate planner recipe references, complete composition and price/portion bounds."""
import json, math, sqlite3, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class PlannerCatalogTest(unittest.TestCase):
    def test_catalogue_structure_and_meals(self):
        data=json.loads((ROOT/'app/src/main/assets/planner_meals.json').read_text());ids=set();covered=0
        for food in data['foods'].values():
            self.assertTrue(food['code'].startswith(('IFCT-','USDA-')));self.assertTrue(food['ingredients_known']);self.assertTrue(food['state']);self.assertTrue(food['tags'])
            self.assertTrue(math.isfinite(food['reference_price_per_100g']));self.assertGreaterEqual(food['reference_price_per_100g'],0)
        for recipe in data['recipes']:
            self.assertNotIn(recipe['id'],ids);ids.add(recipe['id']);covered|=recipe['meal_mask']
            self.assertGreater(recipe['yield_grams'],0);self.assertTrue(recipe['instructions']);self.assertLessEqual(recipe['prep_minutes'],45)
            for item in recipe['ingredients']:
                self.assertIn(item['food'],data['foods']);self.assertGreater(item['grams'],0)
        self.assertEqual(15,covered);self.assertGreaterEqual(len(ids),30)
    def test_pinned_nutrition_references_when_catalogue_is_built(self):
        path=ROOT/'app/src/main/assets/offline_foods.db'
        if not path.exists(): self.skipTest('Full catalogue is generated before Android CI')
        data=json.loads((ROOT/'app/src/main/assets/planner_meals.json').read_text())
        with sqlite3.connect(f'file:{path}?mode=ro',uri=True) as db:
            for food in data['foods'].values():
                row=db.execute('SELECT calories_100g,protein_100g,carbs_100g,fat_100g FROM foods WHERE code=?',(food['code'],)).fetchone()
                self.assertIsNotNone(row,food['code']);self.assertTrue(all(v is not None and math.isfinite(v) and v>=0 for v in row),food['code'])
if __name__=='__main__':unittest.main()
