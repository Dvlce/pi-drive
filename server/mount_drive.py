#!/usr/bin/env python3
"""Privileged helper: mount existing filesystems; never format or erase."""
import argparse
import os
from pathlib import Path
import pwd
import subprocess
from disks import find_volume


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('disk_id')
    args = parser.parse_args()
    volume = find_volume(args.disk_id)
    if not volume['mount']:
        subprocess.run(['udisksctl', 'mount', '--no-user-interaction', '-b', volume['device']],
                       check=True, timeout=30, capture_output=True)
    volume = find_volume(args.disk_id)
    if not volume['mount'] or not Path(volume['mount']).is_mount():
        raise ValueError('Montaggio non riuscito')
    root = Path(volume['mount']) / 'PiDrive'
    if root.is_symlink():
        raise ValueError('Cartella non sicura')
    root.mkdir(mode=0o700, exist_ok=True)
    user = pwd.getpwnam('dvlce')
    # Change only our archive folder, never the existing contents of the drive.
    os.chown(root, user.pw_uid, user.pw_gid)
    os.chmod(root, 0o700)


if __name__ == '__main__':
    main()
