import hashlib, importlib.util, json, tempfile, unittest, zipfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('prepare_sdk',Path(__file__).parents[1]/'prepare-sdk.py');sdk=importlib.util.module_from_spec(spec);spec.loader.exec_module(sdk)
class SharedSdkTest(unittest.TestCase):
    def test_rejects_corruption_and_wrong_protocol(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);page=b'<p>shared</p>'
            with zipfile.ZipFile(folder/'cnote-sdk.aar','w') as archive:
                for name,value in {'classes.jar':b'jar','assets/cnote/index.html':page,'assets/cnote/features.html':page,'assets/cnote/pages.json':json.dumps(dict(protocol=1,sha256=hashlib.sha256(page).hexdigest(),featuresSha256=hashlib.sha256(page).hexdigest())).encode(),'jni/x86_64/libcnote_core.so':b'ELF'}.items():archive.writestr(name,value)
            digest=hashlib.sha256((folder/'cnote-sdk.aar').read_bytes()).hexdigest()
            (folder/'cnote-sdk.aar.sha256').write_text(digest+'  cnote-sdk.aar\n')
            metadata=dict(protocol=1,version='0.1.0',dirty=False,sha256=digest,abis=['x86_64'],features=['notes','trello','onedrive'])
            (folder/'sdk.json').write_text(json.dumps(metadata))
            lock=dict(metadata);self.assertEqual(sdk.verify(folder,lock)['sha256'],digest)
            lock['protocol']=2
            with self.assertRaisesRegex(ValueError,'protocol'):sdk.verify(folder,lock)
            lock['protocol']=1;(folder/'cnote-sdk.aar').write_bytes(b'corrupted')
            with self.assertRaisesRegex(ValueError,'checksum'):sdk.verify(folder,lock)

class SharedSourceTest(unittest.TestCase):
    def fixture(self, folder, unsafe=False):
        import io, tarfile
        page=b'<p>portable</p>'
        pages=dict(protocol=1,sha256=hashlib.sha256(page).hexdigest(),featuresSha256=hashlib.sha256(page).hexdigest())
        with tarfile.open(folder/'cnote-shared.tar.gz','w:gz') as archive:
            files={'shared/core/Cargo.toml':b'[package]','pages/index.html':page,'pages/features.html':page,'pages/pages.json':json.dumps(pages).encode()}
            if unsafe: files['../escaped']=b'bad'
            for name,data in files.items():
                entry=tarfile.TarInfo(name);entry.size=len(data);archive.addfile(entry,io.BytesIO(data))
        digest=hashlib.sha256((folder/'cnote-shared.tar.gz').read_bytes()).hexdigest()
        metadata=dict(protocol=1,version='0.1.0',dirty=False,sha256=digest,features=['notes','trello','onedrive'])
        (folder/'shared.json').write_text(json.dumps(metadata));(folder/'cnote-shared.tar.gz.sha256').write_text(digest+'  cnote-shared.tar.gz\n')
        return metadata
    def test_source_checksum_version_and_page_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);metadata=self.fixture(folder);lock=dict(metadata)
            self.assertEqual(sdk.verify_source(folder,lock)['sha256'],metadata['sha256'])
            dest=folder/'extracted';dest.mkdir();sdk.extract_source(folder,dest)
            self.assertTrue((dest/'pages/features.html').is_file())
            lock['version']='0.2.0'
            with self.assertRaisesRegex(ValueError,'version'):sdk.verify_source(folder,lock)
            (folder/'cnote-shared.tar.gz').write_bytes(b'corrupted')
            with self.assertRaisesRegex(ValueError,'checksum'):sdk.verify_source(folder,metadata)
    def test_source_rejects_archive_traversal_before_extracting(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);self.fixture(folder,unsafe=True);dest=folder/'extracted';dest.mkdir()
            with self.assertRaisesRegex(ValueError,'Unsafe'):sdk.extract_source(folder,dest)
            self.assertFalse((folder/'escaped').exists());self.assertEqual(list(dest.iterdir()),[])

class SharedDownloadTest(unittest.TestCase):
    def test_missing_asset_explains_cnote_release_dependency(self):
        from unittest.mock import patch
        from urllib.error import HTTPError
        url='https://github.com/mnhkahn/cnote/releases/download/v0.3.0/shared.json'
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)/'shared.json'
            with patch.object(sdk.urllib.request,'urlopen',side_effect=HTTPError(url,404,'Not Found',None,None)):
                with self.assertRaises(SystemExit) as failure:
                    sdk.download_source_asset(url,output,dict(repository='mnhkahn/cnote',release='v0.3.0'))
            self.assertIn('v0.3.0',str(failure.exception))
            self.assertIn('workflow artifact is not a Release asset',str(failure.exception))
            self.assertFalse(output.exists())

class LatestReleaseTest(unittest.TestCase):
    def release(self, tag='v0.3.0'):
        return dict(tag_name=tag,draft=False,prerelease=False,assets=[dict(name=name) for name in ['cnote-shared.tar.gz','cnote-shared.tar.gz.sha256','shared.json']])
    def test_uses_latest_version_without_a_version_in_configuration(self):
        import io
        from unittest.mock import patch
        with patch.object(sdk.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(self.release('v0.4.0')).encode())) as request:
            resolved=sdk.resolve_latest_release(dict(repository='mnhkahn/cnote',protocol=1))
        self.assertEqual(resolved['release'],'v0.4.0');self.assertEqual(resolved['version'],'0.4.0')
        self.assertTrue(request.call_args.args[0].full_url.endswith('/releases/latest'))
    def test_rejects_latest_release_without_shared_assets(self):
        import io
        from unittest.mock import patch
        release=self.release();release['assets']=[]
        with patch.object(sdk.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(release).encode())):
            with self.assertRaisesRegex(SystemExit,'v0.3.0.*missing shared assets'):sdk.resolve_latest_release(dict(repository='mnhkahn/cnote'))
    def test_downloads_one_resolved_tag_even_if_a_new_release_appears(self):
        import io
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);package=root/'fixture';package.mkdir();SharedSourceTest().fixture(package)
            config=dict(repository='mnhkahn/cnote',protocol=1,features=['notes','trello','onedrive'],abis=['x86_64'])
            (root/'shared-sdk.json').write_text(json.dumps(config))
            requests=[]
            def open_url(request,**kwargs):
                url=request.full_url if isinstance(request,sdk.urllib.request.Request) else request
                requests.append(url)
                if url.endswith('/releases/latest'):
                    # A second latest query would see the next release.
                    tag='v0.1.0' if requests.count(url)==1 else 'v0.2.0'
                    return io.BytesIO(json.dumps(self.release(tag)).encode())
                self.assertIn('/releases/download/v0.1.0/',url)
                return io.BytesIO((package/url.rsplit('/',1)[-1]).read_bytes())
            with patch.object(sdk,'ROOT',root),patch.object(sdk.urllib.request,'urlopen',side_effect=open_url),patch('sys.argv',['prepare-sdk.py','--source-only']):sdk.main()
            self.assertEqual(sum(url.endswith('/releases/latest') for url in requests),1)
            self.assertEqual(len(requests),4)
            self.assertEqual(json.loads((root/'.shared-source/shared.json').read_text())['version'],'0.1.0')
