#!/usr/bin/env python3
"""Build the Android-owned JNI bridge and AAR using verified portable sources, with reproducible ABI/provenance metadata."""
import argparse, hashlib, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TARGETS={"arm64-v8a":("aarch64-linux-android","aarch64-linux-android"),"armeabi-v7a":("armv7-linux-androideabi","armv7a-linux-androideabi"),"x86_64":("x86_64-linux-android","x86_64-linux-android")}
def run(args,**kw): subprocess.run([str(a) for a in args],check=True,**kw)
def source_digest():
    digest=hashlib.sha256()
    for base in [ROOT/'native', ROOT/'sdk-packaging', ROOT/'.shared-source']:
        for file in sorted(base.rglob('*')):
            rel=file.relative_to(base)
            if file.is_file() and not any(part in ('target','build','.gradle','.kotlin') for part in rel.parts):
                digest.update(str(file.relative_to(ROOT)).encode()+b'\0'+file.read_bytes())
    for name in ['scripts/build-android-sdk.py', 'scripts/prepare-sdk.py', 'gradlew', 'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar']:
        digest.update(name.encode()+b'\0'+(ROOT/name).read_bytes())
    return digest.hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--abis',default=','.join(TARGETS));parser.add_argument('--out',type=Path,default=ROOT/'dist/android-sdk');args=parser.parse_args()
    source_sha=source_digest()
    abis=args.abis.split(',');assert abis and len(set(abis))==len(abis) and all(a in TARGETS for a in abis),'Unsupported or duplicate ABI'
    sdk=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk'))))
    ndk=Path(os.environ['ANDROID_NDK_HOME']) if os.environ.get('ANDROID_NDK_HOME') else sdk/'ndk'/'27.0.12077973'
    host='darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
    tools=ndk/'toolchains/llvm/prebuilt'/host/'bin';assert tools.is_dir(),f'Android NDK r27 missing: {ndk}'
    rust=ROOT/'native';jni=ROOT/'sdk-packaging/sdk/build/generated/jniLibs'
    source=ROOT/'.shared-source'
    metadata=json.loads((source/'shared.json').read_text())
    assets=ROOT/'sdk-packaging/sdk/build/generated/assets/cnote'
    if assets.exists(): shutil.rmtree(assets)
    shutil.copytree(source/'pages',assets)
    # Never include stale libraries from a previous build with different ABIs.
    if jni.exists(): shutil.rmtree(jni)
    env=os.environ.copy();env['ANDROID_HOME']=str(sdk);env['CNOTE_APP_VERSION']=metadata['version']
    env['CARGO_TARGET_DIR']=str(rust/'target')
    env['CARGO_BUILD_JOBS']=env.get('CARGO_BUILD_JOBS','2')
    for abi in abis:
        target,clang=TARGETS[abi];run(['rustup','target','add',target])
        target_env=env.copy();key=target.replace('-','_');compiler=tools/(clang+'26-clang')
        target_env['CARGO_TARGET_'+key.upper()+'_LINKER']=str(compiler)
        target_env['CC_'+key]=str(compiler);target_env['CXX_'+key]=str(compiler)+'++';target_env['AR_'+key]=str(tools/'llvm-ar')
        target_env['CARGO_TARGET_'+key.upper()+'_RUSTFLAGS']='-C link-arg=-Wl,-z,max-page-size=16384'
        run(['cargo','build','--manifest-path',rust/'Cargo.toml','--locked','--release','--target',target],cwd=ROOT,env=target_env)
        headers=subprocess.check_output([str(tools/'llvm-readelf'),'--program-headers','--wide',str(rust/'target'/target/'release/libcnote_core.so')],text=True)
        loads=[line.split() for line in headers.splitlines() if line.strip().startswith('LOAD')]
        assert loads and all(int(parts[-1],16)>=16384 for parts in loads), f'{abi}: ELF is not 16 KiB aligned'
        dest=jni/abi;dest.mkdir(parents=True);shutil.copy2(rust/'target'/target/'release/libcnote_core.so',dest/'libcnote_core.so')
    assert source_digest()==source_sha, 'SDK source changed during native build; rerun to avoid mixed versions'
    run([ROOT/'gradlew','-p',ROOT/'sdk-packaging',':sdk:assembleRelease'],cwd=ROOT,env=env)
    assert source_digest()==source_sha, 'SDK source changed during packaging; rerun to avoid mixed versions'
    args.out.mkdir(parents=True,exist_ok=True);aar=args.out/'cnote-sdk.aar';shutil.copy2(ROOT/'sdk-packaging/sdk/build/outputs/aar/sdk-release.aar',aar)
    with zipfile.ZipFile(aar) as z:
        for abi in abis: assert f'jni/{abi}/libcnote_core.so' in z.namelist()
        assert 'classes.jar' in z.namelist() and 'assets/cnote/index.html' in z.namelist()
    sha=hashlib.sha256(aar.read_bytes()).hexdigest();commit=metadata['commit']
    dirty=metadata['dirty'] or bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip())
    metadata=dict(protocol=1,version=metadata['version'],commit=commit,dirty=dirty,sourceSha256=metadata['sourceSha256'],packagingSha256=source_sha,packagingCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),abis=abis,sha256=sha,features=['notes','trello','onedrive'])
    (args.out/'sdk.json').write_text(json.dumps(metadata,indent=2)+'\n')
    (args.out/'cnote-sdk.aar.sha256').write_text(sha+'  cnote-sdk.aar\n')
    print(aar)
if __name__=='__main__': main()
