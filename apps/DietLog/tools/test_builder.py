import sqlite3, tempfile, unittest
from pathlib import Path
import subprocess, sys

ROOT=Path(__file__).resolve().parents[1]

class MealRecommendationsTest(unittest.TestCase):
    def setUp(self):
        from tools.build_food_db import SCHEMA, insert, payload
        from tools.meal_recommendations import definitions
        self.meals = definitions()
        self.c = sqlite3.connect(':memory:')
        self.c.executescript(SCHEMA)
        for code in {e['code'] for entries in self.meals.values() for e in entries}:
            insert(self.c, payload(code=code, name='Source ' + code, source=code.split('-')[0],
                                  calories_100g=140, protein_100g=4, carbs_100g=25, fat_100g=2))

    def tearDown(self):
        self.c.close()

    def test_meals_cover_requested_food_groups_and_have_distinct_orders(self):
        codes = {meal: [e['code'] for e in entries] for meal, entries in self.meals.items()}
        self.assertTrue({'INDB-ASC144', 'INDB-OSR103', 'USDA-173424', 'IFCT-L002'} <= set(codes['breakfast']))
        self.assertFalse({'INDB-ASC113', 'INDB-BFP240', 'INDB-ASC240'} & set(codes['breakfast']))
        self.assertTrue({'INDB-ASC113', 'INDB-BFP172', 'INDB-BFP269', 'INDB-ASC240'} <= set(codes['lunch']))
        self.assertTrue({'IFCT-E012', 'IFCT-E028', 'USDA-173806'} <= set(codes['snacks']))
        self.assertFalse({'INDB-ASC113', 'INDB-BFP240'} & set(codes['snacks']))
        self.assertTrue({'INDB-ASC096', 'INDB-ASC113', 'INDB-ASC155', 'INDB-BFP239'} <= set(codes['dinner']))
        self.assertEqual('INDB-ASC096', codes['dinner'][0])
        self.assertEqual(4, len({tuple(entries) for entries in codes.values()}))

    def test_database_meal_lookup_preserves_order_labels_and_nutrition(self):
        from tools.meal_recommendations import build_meal_recommendations
        before = self.c.execute('SELECT * FROM foods ORDER BY code').fetchall()
        build_meal_recommendations(self.c, strict=True)
        for meal, entries in self.meals.items():
            rows = self.c.execute('SELECT f.code,m.display_name,f.calories_100g FROM meal_foods m '
                                  'JOIN foods f ON f.code=m.code WHERE m.meal_type=? ORDER BY m.position', (meal,)).fetchall()
            self.assertEqual([e['code'] for e in entries], [r[0] for r in rows])
            self.assertEqual([e.get('name') for e in entries], [r[1] for r in rows])
            self.assertTrue(all(r[2] == 140 for r in rows))
        self.assertEqual(before, self.c.execute('SELECT * FROM foods ORDER BY code').fetchall())
        self.assertEqual('1', self.c.execute("SELECT value FROM metadata WHERE key='meal_suggestions_version'").fetchone()[0])

    def test_missing_production_food_fails_instead_of_keyword_substitution(self):
        from tools.meal_recommendations import build_meal_recommendations
        self.c.execute("DELETE FROM foods WHERE code='INDB-ASC144'")
        with self.assertRaisesRegex(ValueError, 'Missing meal suggestion: INDB-ASC144'):
            build_meal_recommendations(self.c, strict=True)

    def test_incomplete_nutrition_or_wrong_source_cannot_be_recommended(self):
        from tools.meal_recommendations import build_meal_recommendations
        self.c.execute("UPDATE foods SET fat_100g=NULL WHERE code='INDB-ASC144'")
        with self.assertRaisesRegex(ValueError, 'complete source nutrition'):
            build_meal_recommendations(self.c, strict=True)
        self.c.execute("UPDATE foods SET fat_100g=2,source='OFF' WHERE code='INDB-ASC144'")
        with self.assertRaisesRegex(ValueError, 'complete source nutrition'):
            build_meal_recommendations(self.c, strict=True)

    def test_small_builder_fixtures_can_omit_recommendations_without_phantom_foods(self):
        from tools.meal_recommendations import build_meal_recommendations
        self.c.execute("DELETE FROM foods WHERE code != 'INDB-ASC144'")
        build_meal_recommendations(self.c)
        rows = self.c.execute('SELECT meal_type,code FROM meal_foods ORDER BY meal_type').fetchall()
        self.assertEqual([('breakfast', 'INDB-ASC144'), ('dinner', 'INDB-ASC144')], rows)

class SearchIndexTest(unittest.TestCase):
    def setUp(self):
        from tools.build_food_db import SCHEMA, insert, payload
        from tools.food_search_index import build_search_index
        self.c = sqlite3.connect(':memory:')
        self.c.executescript(SCHEMA)
        for code, name, source, brand in [
                ('INDB-OSR103', 'Moong bean dosa (Pesarattu)', 'INDB', None),
                ('INDB-ASC127', 'Tamarind rice (Chintapandu pulihora)', 'INDB', None),
                ('INDB-ASC126', 'Curd rice (Perugu annam)', 'INDB', None),
                ('OFF-001', 'Upma mix', 'OFF', 'Example'),
                ('INDB-BFP039', 'Semolina upma (Suji/Rava upma)', 'INDB', None),
                ('OFF-002', 'Chiken curry', 'OFF', 'Example')]:
            insert(self.c, payload(code=code, name=name, source=source, brand=brand,
                                  calories_100g=140, protein_100g=4, carbs_100g=25, fat_100g=2))
        build_search_index(self.c)

    def tearDown(self):
        self.c.close()

    def matches(self, expression):
        return [r[0] for r in self.c.execute('SELECT f.code FROM foods_search s JOIN foods f '
            'ON f.rowid=s.docid WHERE foods_search MATCH ?', (expression,))]

    def test_telugu_and_transliteration_match_existing_dishes(self):
        self.assertEqual(['INDB-OSR103'], self.matches('"పెసరట్టు*"'))
        self.assertEqual(['INDB-OSR103'], self.matches('"pesaratu*"'))
        self.assertEqual(['INDB-ASC126'], self.matches('"perugu*" AND "annam*"'))

    def test_words_match_in_any_order_and_prefixes(self):
        self.assertEqual(['INDB-ASC127'], self.matches('"rice*" AND "tam*"'))
        self.assertEqual(['OFF-001'], self.matches('"example*" AND "upma*"'))

    def test_popular_foods_exclude_packaged_mixes(self):
        rows = self.c.execute('SELECT f.source FROM regional_foods r JOIN foods f ON f.code=r.code '
                              'WHERE r.popular=1').fetchall()
        self.assertEqual(4, len(rows))
        self.assertTrue(all(r[0] == 'INDB' for r in rows))

    def test_typo_index_can_retrieve_a_transposed_term(self):
        # pesarattu -> pesartatu: two single deletions share a candidate key.
        query = 'pesartatu'
        deletions = {query[:i] + query[i+1:] for i in range(len(query))}
        candidates = {r[0] for d in deletions for r in self.c.execute(
            'SELECT term FROM search_deletions WHERE deletion=?', (d,))}
        self.assertIn('pesarattu', candidates)

    def test_unknown_recipe_does_not_become_a_different_recipe(self):
        self.assertEqual([], self.matches('"gongura*" AND "pappu*"'))

    def test_normalization_preserves_telugu_and_folds_accents(self):
        from tools.food_search_index import normalize
        self.assertEqual('cafe curd rice', normalize(' CAFÉ / curd-rice '))
        self.assertEqual('పెసరట్టు', normalize('పెసరట్టు'))

    def test_every_food_is_indexed_once(self):
        self.assertEqual(6, self.c.execute('SELECT count(*) FROM foods_search').fetchone()[0])
        self.assertEqual('1', self.c.execute("SELECT value FROM metadata WHERE key='search_version'").fetchone()[0])

    def test_misspelled_catalogue_names_are_indexed_under_canonical_spelling(self):
        self.assertEqual(['OFF-002'], self.matches('"chicken*" AND "curry*"'))

class BuilderTest(unittest.TestCase):
    def test_merge_fixture_and_micronutrient_units(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'foods.db'
            xlsx=Path(d)/'indb.xlsx'
            from openpyxl import Workbook
            wb=Workbook(); ws=wb.active; ws.title='Nutrient Data'
            headers=[
                'food_code','food_name','energy_kcal','unit_serving_energy_kcal','protein_g','carb_g','fat_g','fibre_g','servings_unit',
                'calcium_mg','phosphorus_mg','magnesium_mg','sodium_mg','potassium_mg','iron_mg','copper_mg','selenium_ug',
                'chromium_mg','manganese_mg','zinc_mg','vita_ug','vite_mg','vitd2_ug','vitd3_ug','vitk1_ug','vitk2_ug',
                'folate_ug','vitb1_mg','vitb2_mg','vitb3_mg','vitb5_mg','vitb6_mg','vitb7_ug','vitb9_ug','vitc_mg'
            ]
            ws.append(headers)
            ws.append([
                '001','Idli',140,84,4.0,28.0,1.0,2.0,'2 pieces',
                120,150,35,300,180,1.2,0.2,8,0.03,0.4,0.8,70,1.5,1.2,0.8,20,5,
                42,0.1,0.08,1.0,0.25,0.2,2.5,30,4.0
            ])
            wb.save(xlsx)
            subprocess.run([sys.executable,str(ROOT/'tools/build_food_db.py'),
                '--off',str(ROOT/'fixtures/off.csv'),'--openlabel',str(ROOT/'fixtures/openlabel.ts'),
                '--ifct',str(ROOT/'fixtures/ifct.csv'),'--usda',str(ROOT/'fixtures/usda.csv'),
                '--indb',str(xlsx),'--output',str(out)],check=True)
            c=sqlite3.connect(out); c.row_factory=sqlite3.Row
            self.assertEqual(4,c.execute('select count(*) from foods').fetchone()[0])

            packaged=c.execute("select * from foods where barcode='890100000001'").fetchone()
            self.assertEqual('Oats, spices, salt',packaged['ingredients'])
            self.assertEqual(65.0,packaged['carbs_100g'])
            self.assertAlmostEqual(440.0, packaged['sodium_mg_100g'], places=6)
            self.assertIsNone(packaged['calcium_mg_100g'])

            ifct=c.execute("select * from foods where source='IFCT'").fetchone()
            self.assertAlmostEqual(10.0, ifct['calcium_mg_100g'])
            self.assertAlmostEqual(1.5, ifct['iron_mg_100g'])
            self.assertAlmostEqual(5.0, ifct['selenium_ug_100g'])
            self.assertAlmostEqual(50.0, ifct['vitamin_a_ug_100g'])
            self.assertAlmostEqual(1.0, ifct['vitamin_e_mg_100g'])
            self.assertAlmostEqual(2.0, ifct['vitamin_d_ug_100g'])
            self.assertAlmostEqual(10.0, ifct['vitamin_c_mg_100g'])

            indb=c.execute("select * from foods where source='INDB'").fetchone()
            self.assertEqual('2 pieces',indb['serving_size'])
            self.assertAlmostEqual(30.0, indb['chromium_ug_100g'])
            self.assertAlmostEqual(2.0, indb['vitamin_d_ug_100g'])
            self.assertAlmostEqual(25.0, indb['vitamin_k_ug_100g'])
            self.assertAlmostEqual(42.0, indb['folate_ug_100g'])
            self.assertAlmostEqual(4.0, indb['vitamin_c_mg_100g'])

            usda=c.execute("select * from foods where source='USDA'").fetchone()
            self.assertIsNone(usda['calcium_mg_100g'])
            self.assertIsNone(usda['vitamin_c_mg_100g'])

            self.assertEqual('2', c.execute("select value from metadata where key='schema_version'").fetchone()[0])
            self.assertEqual(4,c.execute('select count(*) from foods where calories_100g is not null').fetchone()[0])
            c.close()

if __name__=='__main__': unittest.main()
