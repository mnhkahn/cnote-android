import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release
import publish_pgyer


class ReleaseTests(unittest.TestCase):
    def test_codes_can_upgrade_the_existing_1000_build(self):
        self.assertEqual(1000, release.version_code('0.1.0'))
        self.assertEqual(1001, release.version_code('v0.1.1'))
        self.assertGreater(release.version_code('1.0.0'), release.version_code('0.999.999'))
        for version in ['main', '0.1.1-beta', '1.1000.0', '1.0.1000', '2001.0.0', '1.2.3\nnew']:
            with self.assertRaises(ValueError):
                release.version_code(version)

    def test_manifest_describes_the_exact_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'; apk.write_bytes(b'APK fixture')
            metadata = dict(packageName='com.cyeam.cnote', versionCode=1001, versionName='0.1.1')
            with patch.object(release, 'apk_info', return_value=metadata):
                result = release.manifest(apk, 'v0.1.1', 'Notes')
            self.assertEqual(apk.stat().st_size, result['sizeBytes'])
            self.assertEqual(64, len(result['sha256']))
            self.assertEqual('https://github.com/mnhkahn/cnote-android/releases/download/v0.1.1/CNote-release.apk', result['downloadUrl'])
            with patch.object(release, 'apk_info', return_value={**metadata, 'packageName': 'wrong.app'}):
                with self.assertRaises(ValueError):
                    release.manifest(apk, 'v0.1.1', '')

    def test_publication_waits_until_the_apk_is_available(self):
        metadata = dict(packageName='com.cyeam.cnote', versionCode=1001, versionName='0.1.1')
        token = dict(code=0, data=dict(endpoint='https://upload.example', params={}, key='opaque-build-key'))
        ready = dict(code=0, data=dict(buildIdentifier='com.cyeam.cnote', buildVersionNo='1001', buildVersion='0.1.1', buildShortcutUrl='CNoteTest'))
        with patch.object(publish_pgyer, 'apk_info', return_value=metadata), patch.object(publish_pgyer, 'upload') as upload, patch.object(publish_pgyer, 'api', side_effect=[token, {'code':1246}, {'code':1247}, ready]), patch.object(publish_pgyer.time, 'sleep'):
            result = publish_pgyer.publish(Path('app.apk'), 'Notes', 'test-key')
            upload.assert_called_once()
        self.assertEqual('https://www.pgyer.com/CNoteTest', result['url'])
        self.assertNotIn('test-key', json.dumps(result))

    def test_publication_rejects_terminal_failure_and_wrong_package(self):
        metadata = dict(packageName='com.cyeam.cnote', versionCode=1001, versionName='0.1.1')
        token = dict(code=0, data=dict(endpoint='https://upload.example', params={}, key='build'))
        for response in [dict(code=1002),dict(code=0,data=dict(buildIdentifier='wrong.app',buildVersionNo=1001,buildVersion='0.1.1'))]:
            with patch.object(publish_pgyer, 'apk_info', return_value=metadata), patch.object(publish_pgyer, 'upload'), patch.object(publish_pgyer, 'api', side_effect=[token,response]):
                with self.assertRaises(ValueError):
                    publish_pgyer.publish(Path('app.apk'), '', 'test-key')


if __name__ == '__main__':
    unittest.main()
