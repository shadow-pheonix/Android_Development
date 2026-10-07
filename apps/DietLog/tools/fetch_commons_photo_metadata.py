#!/usr/bin/env python3
"""Resolve free lead photos for reviewed food groups, preserving Commons credits."""
import argparse
import html
import hashlib
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

USER_AGENT = 'DietLog/0.7.11 (https://github.com/shadow-pheonix/Android_Development)'


def request_json(base, parameters, cache):
    if cache.is_file():
        return json.loads(cache.read_text(encoding='utf-8'))
    parameters = {'action': 'query', 'format': 'json', 'maxlag': 5, **parameters}
    request = urllib.request.Request(base + '?' + urllib.parse.urlencode(parameters),
                                     headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=45) as response:
        data = json.load(response)
    if 'error' in data:
        raise RuntimeError('Wikimedia API error: ' + str(data['error'].get('code')))
    cache.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    return data


def plain(value):
    return html.unescape(re.sub('<[^>]+>', '', value or '')).strip()


def allowed_license(name, url):
    if name.lower() in ('public domain', 'cc0'):
        return True
    return name.lower().startswith(('cc by ', 'cc by-sa ')) and url.startswith('https://creativecommons.org/')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--queries', required=True)
    parser.add_argument('--cache', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    groups = json.loads(Path(args.queries).read_text(encoding='utf-8'))['groups']
    groups = [g for g in groups if g.get('enabled', True)]
    cache = Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)
    titles_to_files = {}
    for offset in range(0, len(groups), 20):
        batch = groups[offset:offset + 20]
        data = request_json('https://en.wikipedia.org/w/api.php', {
            'titles': '|'.join(g['title'] for g in batch), 'redirects': 1,
            'prop': 'pageimages', 'piprop': 'name', 'pilicense': 'free',
        }, cache / ('wiki-' + hashlib.sha256('|'.join(g['title'] for g in batch).encode()).hexdigest() + '.json'))
        query = data.get('query', {})
        redirects = {r['from']: r['to'] for r in query.get('normalized', []) + query.get('redirects', [])}
        pages = {p['title']: p for p in query.get('pages', {}).values()}
        for group in batch:
            title = group['title']
            visited = set()
            while title in redirects and title not in visited:
                visited.add(title)
                title = redirects[title]
            filename = pages.get(title, {}).get('pageimage')
            if filename and filename.lower().endswith(('.jpg', '.jpeg', '.png', '.webp')):
                titles_to_files[group['id']] = 'File:' + filename.replace('_', ' ')
        print('Wikipedia food groups resolved:', min(offset + 20, len(groups)), flush=True)

    for group in groups:
        if group.get('file_title'):
            titles_to_files[group['id']] = group['file_title']

    file_info = {}
    filenames = sorted(set(titles_to_files.values()))
    for offset in range(0, len(filenames), 20):
        data = request_json('https://commons.wikimedia.org/w/api.php', {
            'titles': '|'.join(filenames[offset:offset + 20]), 'prop': 'imageinfo',
            'iiprop': 'url|extmetadata', 'iiurlwidth': 256,
        }, cache / ('commons-' + hashlib.sha256('|'.join(filenames[offset:offset + 20]).encode()).hexdigest() + '.json'))
        for page in data.get('query', {}).get('pages', {}).values():
            infos = page.get('imageinfo') or []
            if not infos:
                continue
            info = infos[0]
            metadata = info.get('extmetadata') or {}
            license_name = plain(metadata.get('LicenseShortName', {}).get('value', ''))
            license_url = plain(metadata.get('LicenseUrl', {}).get('value', ''))
            if license_url.startswith('//'):
                license_url = 'https:' + license_url
            license_url = license_url.replace('http://', 'https://', 1)
            if not allowed_license(license_name, license_url):
                continue
            artist = plain(metadata.get('Artist', {}).get('value', ''))
            if not artist:
                continue
            url = info.get('thumburl')
            if not url or not url.startswith('https://'):
                continue
            file_info[page['title']] = {
                'url': url, 'source_url': info['descriptionurl'].replace('http://', 'https://', 1),
                'credit': artist[:4000], 'license': license_name,
                'license_url': license_url or 'https://commons.wikimedia.org/wiki/Commons:Copyright_tags',
                'kind': 'representative', 'title': page['title'],
            }
        print('Commons photo credits checked:', min(offset + 20, len(filenames)), flush=True)

    photos = {key: file_info[filename] for key, filename in titles_to_files.items() if filename in file_info}
    Path(args.output).write_text(json.dumps({'photos': photos}, ensure_ascii=False, sort_keys=True), encoding='utf-8')
    print('Free attributed food photos:', len(photos), flush=True)


if __name__ == '__main__':
    main()
