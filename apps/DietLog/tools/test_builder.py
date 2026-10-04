import sqlite3, tempfile, unittest
from pathlib import Path
import subprocess, sys

ROOT=Path(__file__).resolve().parents[1]
class BuilderTest(unittest.TestCase):
    def test_merge_fixture(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'foods.db'
            xlsx=Path(d)/'indb.xlsx'
            from openpyxl import Workbook
            wb=Workbook(); ws=wb.active; ws.title='Nutrient Data'
            ws.append(['food_code','food_name','energy_kcal','unit_serving_energy_kcal','protein_g','carb_g','fat_g','fibre_g','servings_unit','iron_mg'])
            ws.append(['001','Idli',140,84,4.0,28.0,1.0,2.0,'2 pieces',1.2]); wb.save(xlsx)
            subprocess.run([sys.executable,str(ROOT/'tools/build_food_db.py'),
                '--off',str(ROOT/'fixtures/off.csv'),'--openlabel',str(ROOT/'fixtures/openlabel.ts'),
                '--ifct',str(ROOT/'fixtures/ifct.csv'),'--usda',str(ROOT/'fixtures/usda.csv'),
                '--indb',str(xlsx),'--output',str(out)],check=True)
            c=sqlite3.connect(out); c.row_factory=sqlite3.Row
            self.assertEqual(4,c.execute('select count(*) from foods').fetchone()[0])
            p=c.execute("select * from foods where barcode='890100000001'").fetchone()
            self.assertEqual('Oats, spices, salt',p['ingredients'])
            self.assertEqual(65.0,p['carbs_100g'])
            self.assertEqual(4,c.execute('select count(*) from foods where calories_100g is not null').fetchone()[0])
            self.assertEqual('2 pieces',c.execute("select serving_size from foods where source='INDB'").fetchone()[0])
if __name__=='__main__': unittest.main()
