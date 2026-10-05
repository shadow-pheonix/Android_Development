import sqlite3, tempfile, unittest
from pathlib import Path
import subprocess, sys

ROOT=Path(__file__).resolve().parents[1]

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
