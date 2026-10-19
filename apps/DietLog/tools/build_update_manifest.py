"""Build release update metadata from the final APK, never from guessed Gradle values."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def build_manifest(apk, aapt):
    apk = Path(apk)
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != 'com.shadowphoenix.dietlog.debug':
        raise ValueError('Update release requires the DietLog debug package')
    size = apk.stat().st_size
    if not 0 < size <= 150 * 1024 * 1024:
        raise ValueError('APK size is outside the supported update bounds')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return dict(schema=1, versionCode=int(match[2]), versionName=match[3],
                packageName=match[1], apkName='DietLog-debug.apk',
                apkSha256=digest, apkSize=size)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    result = build_manifest(args.apk, args.aapt)
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('Prepared update metadata for version ' + result['versionName'])
