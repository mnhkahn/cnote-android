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
