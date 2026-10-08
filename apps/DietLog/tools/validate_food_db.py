#!/usr/bin/env python3
import argparse, sqlite3
from meal_recommendations import definitions
ap=argparse.ArgumentParser(); ap.add_argument('db'); ap.add_argument('--min-foods',type=int,default=1); a=ap.parse_args()
c=sqlite3.connect(a.db); c.row_factory=sqlite3.Row
count=c.execute('SELECT COUNT(*) n FROM foods').fetchone()['n']
if count<a.min_foods: raise SystemExit(f'expected >= {a.min_foods} foods, got {count}')
required=['foods','metadata','foods_search','regional_foods','search_terms','search_deletions','meal_foods']; tables={r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
if not set(required)<=tables: raise SystemExit(f'missing tables: {set(required)-tables}')
if c.execute('SELECT count(*) FROM foods_search').fetchone()[0] != count:
    raise SystemExit('search index does not cover every food')
if a.min_foods >= 26000:
    popular=c.execute('SELECT count(*) FROM regional_foods WHERE popular=1').fetchone()[0]
    if popular != 16: raise SystemExit(f'expected 16 Andhra favourites, got {popular}')
    invalid=c.execute('SELECT count(*) FROM regional_foods r JOIN foods f ON f.code=r.code WHERE r.popular=1 '
                      "AND (f.source != 'INDB' OR f.calories_100g IS NULL OR f.protein_100g IS NULL "
                      'OR f.carbs_100g IS NULL OR f.fat_100g IS NULL)').fetchone()[0]
    if invalid: raise SystemExit('regional popular foods must have complete cooked-food nutrition')
    for meal, entries in definitions().items():
        actual = [r[0] for r in c.execute('SELECT code FROM meal_foods WHERE meal_type=? ORDER BY position', (meal,))]
        if actual != [entry['code'] for entry in entries]:
            raise SystemExit('missing, extra or misordered meal suggestions: ' + meal)
    invalid=c.execute('SELECT count(*) FROM meal_foods m LEFT JOIN foods f ON f.code=m.code '
                     "WHERE f.code IS NULL OR f.source NOT IN ('INDB','IFCT','USDA') "
                     'OR f.calories_100g IS NULL OR f.protein_100g IS NULL OR f.carbs_100g IS NULL OR f.fat_100g IS NULL').fetchone()[0]
    if invalid: raise SystemExit('meal suggestions must have complete generic/cooked-food nutrition')
for q in ['rice','milk']:
    rows=c.execute('SELECT name,source FROM foods WHERE search_name LIKE ? LIMIT 3',(f'%{q}%',)).fetchall()
    print(q, [dict(x) for x in rows])
print('metadata', dict(c.execute('SELECT key,value FROM metadata').fetchall()))
print('foods', count)
