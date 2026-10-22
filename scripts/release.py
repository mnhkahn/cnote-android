#!/usr/bin/env python3
"""Build the public update manifest from the exact signed APK being published."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGE = 'com.cyeam.cnote'
REPOSITORY = 'mnhkahn/cnote-android'


def version_code(version):
    version = version.removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('A stable semantic version is required')
    major, minor, patch = map(int, version.split('.'))
    if not (0 <= major <= 2000 and 0 <= minor <= 999 and 0 <= patch <= 999):
        raise ValueError('Version components out of range')
    return major * 1_000_000 + minor * 1_000 + patch


def apk_info(apk):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
    aapt = Path(os.environ.get('AAPT2') or sdk / 'build-tools/35.0.0/aapt2')
    result = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", result.stdout)
    if not match:
        raise ValueError('Cannot read APK metadata')
    return dict(packageName=match[1], versionCode=int(match[2]), versionName=match[3])


def manifest(apk, tag, notes):
    code = version_code(tag)
    metadata = apk_info(apk)
    if metadata != dict(packageName=PACKAGE, versionCode=code, versionName=tag.removeprefix('v')):
        raise ValueError('APK metadata does not match this release')
    return dict(appId='cnote', **metadata, tag=tag, releaseNotes=notes,
                sizeBytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                downloadUrl=f'https://github.com/{REPOSITORY}/releases/download/{tag}/CNote-release.apk')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(manifest(args.apk, args.tag, args.notes.read_text()), ensure_ascii=False, indent=2) + '\n')
    print('Update manifest validated against the signed APK.')


if __name__ == '__main__':
    main()
