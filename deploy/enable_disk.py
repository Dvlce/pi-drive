#!/usr/bin/env python3
"""Enable boot mounting for one explicitly chosen existing ext4 volume."""
import argparse
import hashlib
from pathlib import Path
import pwd
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from disks import find_volume

parser = argparse.ArgumentParser()
parser.add_argument('uuid')
args = parser.parse_args()
volume = find_volume(hashlib.sha256(args.uuid.encode()).hexdigest()[:24])
if volume['filesystem'] != 'ext4':
    raise SystemExit('Questo installer automatico richiede un disco ext4 già preparato.')
mount = Path('/srv/pidrive')
if volume['mount'] and volume['mount'] != str(mount):
    raise SystemExit('Il disco è già montato altrove: non modifico i suoi mount esistenti.')
mount.mkdir(parents=True, exist_ok=True)
if not mount.is_mount() and any(mount.iterdir()):
    raise SystemExit('La cartella destinazione contiene dati: installazione interrotta.')
unit = Path('/etc/systemd/system/srv-pidrive.mount')
content = f'''[Unit]
Description=Pi Drive - volume archivio dedicato

[Mount]
What=/dev/disk/by-uuid/{args.uuid}
Where=/srv/pidrive
Type=ext4
Options=nosuid,nodev,noexec
TimeoutSec=15

[Install]
WantedBy=multi-user.target
'''
if unit.exists() and unit.read_text() != content:
    raise SystemExit('Esiste già una configurazione diversa: non la sovrascrivo.')
unit.write_text(content)
subprocess.run(['systemctl', 'daemon-reload'], check=True)
subprocess.run(['systemctl', 'enable', '--now', 'srv-pidrive.mount'], check=True)
if not mount.is_mount():
    raise SystemExit('Disco non montato')
root = mount / 'PiDrive'
if root.is_symlink():
    raise SystemExit('Cartella archivio non sicura')
root.mkdir(exist_ok=True)
user = pwd.getpwnam('dvlce')
root.chmod(0o700)
import os
os.chown(root, user.pw_uid, user.pw_gid)
dropin = Path('/etc/systemd/system/pi-drive.service.d')
dropin.mkdir(exist_ok=True)
(dropin / 'storage.conf').write_text('[Unit]\nWants=srv-pidrive.mount\nAfter=srv-pidrive.mount\n')
print('Disco dedicato pronto. Gli altri dati e mount non sono stati modificati.')
