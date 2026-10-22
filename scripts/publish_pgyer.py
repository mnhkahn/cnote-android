#!/usr/bin/env python3
"""Pgyer fast upload with release confirmation; no keys/tokens are printed or saved."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from release import apk_info

BASE = 'https://www.pgyer.com/apiv2/app/'


def api(name, fields):
    encoded = urllib.parse.urlencode(fields)
    request = urllib.request.Request(BASE + name + '?' + encoded, method='GET') if name == 'buildInfo' else urllib.request.Request(BASE + name, data=encoded.encode(), method='POST')
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.loads(response.read(1024 * 1024))


def upload(endpoint, params, apk):
    url = urllib.parse.urlsplit(endpoint)
    if url.scheme != 'https' or not url.hostname or url.username or url.password:
        raise ValueError('Unsafe upload endpoint')
    boundary = 'cnote-' + uuid.uuid4().hex
    body = bytearray()
    for key, value in params.items():
        if not re.fullmatch(r'[\w-]+', key):
            raise ValueError('Invalid upload field')
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="CNote-release.apk"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n'.encode())
    body.extend(apk.read_bytes())
    body.extend(f'\r\n--{boundary}--\r\n'.encode())
    request = urllib.request.Request(endpoint, data=bytes(body), headers={'Content-Type': f'multipart/form-data; boundary={boundary}'}, method='POST')
    with urllib.request.urlopen(request, timeout=180) as response:
        response.read(1024)


def publish(apk, notes, key):
    expected = apk_info(apk)
    if expected['packageName'] != 'com.cyeam.cnote':
        raise ValueError('Refusing to publish another app')
    token = api('getCOSToken', dict(_api_key=key, buildType='android', buildInstallType=1, buildInstallDate=2, buildUpdateDescription=notes))
    if token.get('code') != 0:
        raise ValueError(f"Pgyer upload-token request failed (code {token.get('code')})")
    data = token['data']
    upload(data['endpoint'], data['params'], apk)
    for attempt in range(60):
        response = api('buildInfo', dict(_api_key=key, buildKey=data['key']))
        code = response.get('code')
        if code == 0:
            build = response['data']
            if build.get('buildIdentifier') != expected['packageName'] or int(build.get('buildVersionNo', -1)) != expected['versionCode'] or build.get('buildVersion') != expected['versionName']:
                raise ValueError('Published APK metadata does not match the signed release')
            shortcut = build['buildShortcutUrl']
            if not re.fullmatch(r'[A-Za-z0-9_-]+', shortcut):
                raise ValueError('Invalid Pgyer shortcut')
            return dict(url='https://www.pgyer.com/' + shortcut, shortcut=shortcut, **expected)
        if code not in (1246, 1247):
            raise ValueError(f'Pgyer publication failed (code {code})')
        print(f'Pgyer processing ({attempt + 1}/60)', flush=True)
        time.sleep(3)
    raise TimeoutError('Pgyer publication did not finish within three minutes')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    key = os.environ.get('PGYER_API_KEY', '').strip()
    if not key:
        raise SystemExit('PGYER_API_KEY is required')
    try:
        result = publish(args.apk, args.notes.read_text(), key)
    except Exception as error:
        # Network exceptions can contain credential-bearing request URLs; never echo them.
        message = str(error) if isinstance(error, (ValueError, TimeoutError)) else type(error).__name__
        raise SystemExit('Publication failed: ' + message) from None
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('url=' + result['url'] + '\n')
    print('Published: ' + result['url'])


if __name__ == '__main__':
    main()
