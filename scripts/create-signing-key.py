#!/usr/bin/env python3
"""Create a stable project-only release key; never print private signing material."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
folder = root / '.signing'
folder.mkdir(mode=0o700, exist_ok=True)
key, config = folder / 'release.jks', folder / 'release.properties'
if key.exists() or config.exists():
    raise SystemExit('Signing files already exist; preserve them for future updates.')
tool = shutil.which('keytool')
if not tool:
    raise SystemExit('Set JAVA_HOME and add its bin directory to PATH first.')
password = secrets.token_hex(32)
env = dict(os.environ, CNOTE_LOCAL_STOREPASS=password)
subprocess.run([tool, '-genkeypair', '-keystore', str(key), '-storetype', 'JKS', '-storepass:env', 'CNOTE_LOCAL_STOREPASS', '-keypass:env', 'CNOTE_LOCAL_STOREPASS', '-alias', 'cnote', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=CNote, O=Cyeam, C=CN'], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
key.chmod(0o600)
fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write('CNOTE_KEYSTORE=.signing/release.jks\nCNOTE_STORE_PASSWORD=' + password + '\n')
print('Created .signing/release.jks and release.properties. Keep both backed up privately.')
