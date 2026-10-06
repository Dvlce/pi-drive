#!/usr/bin/env python3
"""Create a persistent local release key outside the repository and build an APK."""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--java-home', required=True)
parser.add_argument('--android-home', required=True)
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
keys = Path.home() / '.pi-drive-signing'
keys.mkdir(mode=0o700, exist_ok=True)
password_file = keys / 'password'
if not password_file.exists():
    password_file.write_text(secrets.token_urlsafe(32))
    password_file.chmod(0o600)
password = password_file.read_text().strip()
keystore = keys / 'release.jks'
if not keystore.exists():
    subprocess.run([str(Path(args.java_home) / 'bin/keytool'), '-genkeypair', '-keystore',
        str(keystore), '-storepass', password, '-keypass', password, '-alias', 'pidrive',
        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Pi Drive, O=Dvlce, C=IT'], check=True)
    keystore.chmod(0o600)
config = project / 'android/keystore.properties'
config.write_text(f'storeFile={keystore}\nstorePassword={password}\nkeyAlias=pidrive\nkeyPassword={password}\n')
config.chmod(0o600)
env = dict(os.environ, JAVA_HOME=args.java_home, ANDROID_HOME=args.android_home)
subprocess.run(['./gradlew', ':app:assembleRelease'], cwd=project / 'android', env=env, check=True)
dist = project / 'dist'
dist.mkdir(exist_ok=True)
shutil.copyfile(project / 'android/app/build/outputs/apk/release/app-release.apk', dist / 'Pi-Drive.apk')
print('APK firmato pronto in dist/Pi-Drive.apk. Chiave privata conservata fuori dal repository.')
