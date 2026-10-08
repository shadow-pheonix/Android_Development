"""Build portable FTS4 search and reviewed Telugu aliases, without changing nutrition."""
import collections
import json
import re
import unicodedata
from pathlib import Path

SPELLINGS = {'chiken': 'chicken', 'chciken': 'chicken', 'panner': 'paneer',
             'briyani': 'biryani', 'biriyani': 'biryani', 'basmathi': 'basmati'}


def normalize(value):
    value = unicodedata.normalize('NFKD', value or '').lower()
    value = ''.join(c for c in value if not '\u0300' <= c <= '\u036f')
    return ' '.join(''.join(c if c.isalnum() or unicodedata.category(c).startswith('M')
                            else ' ' for c in value).split())


def build_search_index(conn):
    entries = json.loads(Path(__file__).with_name('andhra_foods.json').read_text())
    assert len({e['code'] for e in entries}) == len(entries), 'Duplicate regional food'
    conn.executescript('''
        CREATE TABLE regional_foods(code TEXT PRIMARY KEY, display_name TEXT NOT NULL,
                                    position INTEGER NOT NULL, popular INTEGER NOT NULL);
        CREATE VIRTUAL TABLE foods_search USING fts4(name, brand, aliases, category,
                                                    tokenize=unicode61, prefix='2,3,4');
        CREATE TABLE search_terms(term TEXT PRIMARY KEY, frequency INTEGER NOT NULL);
        CREATE TABLE search_deletions(deletion TEXT NOT NULL, term TEXT NOT NULL,
                                     PRIMARY KEY(deletion,term)) WITHOUT ROWID;
    ''')
    aliases = {}
    for position, entry in enumerate(entries):
        row = conn.execute('SELECT source,calories_100g,protein_100g,carbs_100g,fat_100g '
                           'FROM foods WHERE code=?', (entry['code'],)).fetchone()
        if row is None:
            continue  # Small fixture catalogues intentionally contain only a few foods.
        if entry['popular'] and (row[0] != 'INDB' or any(v is None for v in row[1:])):
            raise ValueError('Popular food must be a complete cooked dish: ' + entry['code'])
        aliases[entry['code']] = '\n'.join(dict.fromkeys(normalize(a) for a in entry['aliases']))
        conn.execute('INSERT INTO regional_foods VALUES(?,?,?,?)',
                     (entry['code'], entry['name'], position, int(entry['popular'])))
    terms = collections.Counter()
    for row in conn.execute('SELECT rowid,code,name,brand,category FROM foods'):
        rowid, code, name, brand, category = row
        name, brand, category = normalize(name), normalize(brand), normalize(category)
        alias = aliases.get(code, '')
        canonical_name = ' '.join(SPELLINGS.get(t, t) for t in name.split())
        if canonical_name != name:
            alias = '\n'.join(filter(None, [alias, canonical_name]))
        conn.execute('INSERT INTO foods_search(docid,name,brand,aliases,category) VALUES(?,?,?,?,?)',
                     (rowid, name, brand, alias, category))
        terms.update(set(re.findall(r'\b[a-z][a-z0-9]{2,31}\b', name + ' ' + brand + ' ' + alias)))
    conn.executemany('INSERT INTO search_terms VALUES(?,?)', terms.items())
    for term in sorted(terms):
        deletions = {term} | {term[:i] + term[i+1:] for i in range(len(term))}
        conn.executemany('INSERT INTO search_deletions VALUES(?,?)', ((d, term) for d in deletions))
    conn.execute("INSERT INTO foods_search(foods_search) VALUES('optimize')")
    conn.execute('INSERT INTO metadata VALUES(?,?)', ('search_version', '1'))
    return len(aliases), len(terms)
