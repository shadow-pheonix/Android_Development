"""Reviewed meal suggestions use stable IDs, never ambiguous keyword/brand matches."""
import json
import math
from pathlib import Path

MEALS = ('breakfast', 'lunch', 'snacks', 'dinner')


def definitions():
    meals = json.loads(Path(__file__).with_name('meal_foods.json').read_text())
    if set(meals) != set(MEALS):
        raise ValueError('Suggestions must cover breakfast, lunch, snacks and dinner')
    for meal, entries in meals.items():
        codes = [entry['code'] for entry in entries]
        if not entries or len(codes) != len(set(codes)):
            raise ValueError('Empty or duplicate suggestions for ' + meal)
        for entry in entries:
            if entry['code'].split('-')[0] not in ('INDB', 'IFCT', 'USDA'):
                raise ValueError('Meal suggestions must use reviewed generic/cooked foods')
            if 'name' in entry and (not isinstance(entry['name'], str) or not entry['name'].strip()):
                raise ValueError('Invalid suggestion label for ' + entry['code'])
    return meals


def build_meal_recommendations(conn, strict=False):
    """Small builder fixtures may omit foods; full production builds require every ID."""
    meals = definitions()
    rows = []
    for meal, entries in meals.items():
        for position, entry in enumerate(entries):
            food = conn.execute('SELECT source,calories_100g,protein_100g,carbs_100g,fat_100g '
                                'FROM foods WHERE code=?', (entry['code'],)).fetchone()
            if food is None:
                if strict:
                    raise ValueError('Missing meal suggestion: ' + entry['code'])
                continue
            if food[0] != entry['code'].split('-')[0] or any(
                    value is None or not math.isfinite(value) or value < 0 for value in food[1:]):
                raise ValueError('Meal suggestion needs complete source nutrition: ' + entry['code'])
            rows.append((meal, entry['code'], position, entry.get('name')))
    conn.executescript('''
        CREATE TABLE meal_foods(meal_type TEXT NOT NULL, code TEXT NOT NULL,
                               position INTEGER NOT NULL, display_name TEXT,
                               PRIMARY KEY(meal_type,code), UNIQUE(meal_type,position)) WITHOUT ROWID;
    ''')
    conn.executemany('INSERT INTO meal_foods VALUES(?,?,?,?)', rows)
    conn.execute('INSERT INTO metadata VALUES(?,?)', ('meal_suggestions_version', '1'))
    return len(rows)
