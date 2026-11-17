#!/usr/bin/env python3
"""Resolve the latest CNote release once, verify its shared sources, then build the Android AAR."""
import argparse, hashlib, json, os, shutil, tempfile, urllib.request, urllib.error, zipfile, tarfile, subprocess, re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def verify(folder,lock,local=False):
    metadata=json.loads((folder/'sdk.json').read_text())
    if metadata['protocol']!=lock['protocol']: raise ValueError('SDK protocol mismatch')
    if not set(lock['features']).issubset(metadata['features']): raise ValueError('Missing SDK features')
    if not local and (metadata['version']!=lock['version'] or metadata['dirty']): raise ValueError('SDK version mismatch or unpublished source tree')
    expected=metadata['sha256'];actual=hashlib.sha256((folder/'cnote-sdk.aar').read_bytes()).hexdigest()
    if actual!=expected or (folder/'cnote-sdk.aar.sha256').read_text().split()[0]!=actual: raise ValueError('SDK checksum mismatch')
    if lock.get('sha256') and not local and lock['sha256']!=actual: raise ValueError('SDK differs from pinned checksum')
    if not local and not set(lock['abis']).issubset(metadata['abis']): raise ValueError('Missing release ABIs')
    with zipfile.ZipFile(folder/'cnote-sdk.aar') as archive:
        names=set(archive.namelist())
        if not {'classes.jar','assets/cnote/index.html','assets/cnote/pages.json','assets/cnote/features.html'}.issubset(names): raise ValueError('Incomplete SDK archive')
        for abi in metadata['abis']:
            if f'jni/{abi}/libcnote_core.so' not in names: raise ValueError(f'Missing native library: {abi}')
        pages=json.loads(archive.read('assets/cnote/pages.json'))
        if pages['protocol']!=lock['protocol'] or hashlib.sha256(archive.read('assets/cnote/index.html')).hexdigest()!=pages['sha256']: raise ValueError('SDK pages mismatch')
        if hashlib.sha256(archive.read('assets/cnote/features.html')).hexdigest()!=pages['featuresSha256']: raise ValueError('Feature pages mismatch')
    return metadata

def verify_source(folder, lock, local=False):
    metadata=json.loads((folder/'shared.json').read_text())
    if metadata['protocol']!=lock['protocol']: raise ValueError('Source protocol mismatch')
    if not set(lock['features']).issubset(metadata['features']): raise ValueError('Missing source features')
    if not local and (metadata['version']!=lock['version'] or metadata['dirty']): raise ValueError('Source version mismatch or unpublished source tree')
    digest=hashlib.sha256((folder/'cnote-shared.tar.gz').read_bytes()).hexdigest()
    if metadata['sha256']!=digest or (folder/'cnote-shared.tar.gz.sha256').read_text().split()[0]!=digest: raise ValueError('Source checksum mismatch')
    if lock.get('sha256') and not local and lock['sha256']!=digest: raise ValueError('Source differs from pinned checksum')
    return metadata

def extract_source(folder, dest):
    with tarfile.open(folder/'cnote-shared.tar.gz','r:gz') as archive:
        # Only plain files/directories are accepted, with paths inside the destination.
        members=archive.getmembers()
        for entry in members:
            path=Path(entry.name)
            if path.is_absolute() or '..' in path.parts or not (entry.isfile() or entry.isdir()): raise ValueError('Unsafe shared source archive')
        archive.extractall(dest, members=members, filter='data')
    shutil.copyfile(folder/'shared.json',dest/'shared.json')
    for name in ['shared/core/Cargo.toml','pages/index.html','pages/features.html','pages/pages.json']:
        if not (dest/name).is_file(): raise ValueError('Incomplete shared source archive')
    pages=json.loads((dest/'pages/pages.json').read_text())
    if pages['protocol']!=json.loads((dest/'shared.json').read_text())['protocol']: raise ValueError('Shared page protocol mismatch')
    for name,key in [('index.html','sha256'),('features.html','featuresSha256')]:
        if hashlib.sha256((dest/'pages'/name).read_bytes()).hexdigest()!=pages[key]: raise ValueError('Shared page checksum mismatch')

def download_source_asset(url, output, lock):
    try:
        with urllib.request.urlopen(url,timeout=120) as response,output.open('wb') as target:
            shutil.copyfileobj(response,target)
    except urllib.error.HTTPError as error:
        if error.code==404:
            raise SystemExit(
                f"Shared source dependency is unavailable (HTTP 404): {url}\n"
                f"Build and upload shared assets to CNote release {lock['repository']} {lock['release']}: "
                "cnote-shared.tar.gz, cnote-shared.tar.gz.sha256 and shared.json before building Android. "
                "A workflow artifact is not a Release asset. For local development use --local ../cnote."
            ) from None
        raise

def resolve_latest_release(config):
    url=f"https://api.github.com/repos/{config['repository']}/releases/latest"
    headers={'Accept':'application/vnd.github+json','User-Agent':'CNote-Android-SDK'}
    token=os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if token: headers['Authorization']='Bearer '+token
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=120) as response:
            release=json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Cannot resolve latest CNote release: HTTP {error.code}: {url}") from None
    tag=release.get('tag_name','')
    version=re.fullmatch(r'[vV]?(\d+\.\d+\.\d+)',tag)
    if not version or release.get('draft') or release.get('prerelease'):
        raise SystemExit(f"Latest CNote release is not a stable version: {tag}")
    required={'cnote-shared.tar.gz','cnote-shared.tar.gz.sha256','shared.json'}
    missing=required-{asset['name'] for asset in release.get('assets',[])}
    if missing:
        raise SystemExit(f"Latest CNote release {tag} is missing shared assets: {', '.join(sorted(missing))}. "
                         "Publish the shared package to that CNote release before building Android.")
    print(f"Resolved latest CNote release: {tag}")
    return {**config,'release':tag,'version':version.group(1)}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--local',type=Path,help='Portable source package directory or cnote checkout');parser.add_argument('--abis');parser.add_argument('--source-only',action='store_true');args=parser.parse_args()
    lock=json.loads((ROOT/'shared-sdk.json').read_text())
    if not args.local: lock=resolve_latest_release(lock)
    with tempfile.TemporaryDirectory(prefix='cnote-source-') as work:
        folder=Path(work)/'package';folder.mkdir()
        if args.local and (args.local/'scripts/build-shared-source.py').is_file():
            subprocess.run(['python3',str(args.local/'scripts/build-shared-source.py'),'--out',str(folder)],check=True)
        else:
            for name in ['cnote-shared.tar.gz','cnote-shared.tar.gz.sha256','shared.json']:
                if args.local: shutil.copyfile(args.local/name,folder/name)
                else:
                    url=f"https://github.com/{lock['repository']}/releases/download/{lock['release']}/{name}"
                    download_source_asset(url,folder/name,lock)
        metadata=verify_source(folder,lock,local=args.local is not None)
        extracted=Path(work)/'extracted';extracted.mkdir();extract_source(folder,extracted)
        dest=ROOT/'.shared-source'
        if dest.exists(): shutil.rmtree(dest)
        shutil.move(extracted,dest)
    if args.source_only: return
    build=['python3',str(ROOT/'scripts/build-android-sdk.py')]
    if args.abis: build+=['--abis',args.abis]
    subprocess.run(build,check=True)
    folder=ROOT/'dist/android-sdk';verify(folder,lock,local=True)
    dest=ROOT/'.shared-sdk';dest.mkdir(exist_ok=True)
    for name in ['sdk.json','cnote-sdk.aar.sha256','cnote-sdk.aar']:
        tmp=dest/(name+'.tmp');shutil.copyfile(folder/name,tmp);os.replace(tmp,dest/name)
    print(f"Shared {metadata['version']} ({metadata['commit']}) built into Android AAR")
if __name__=='__main__': main()
