"""Discover physical volumes without ever using the operating system disk."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def physical_volumes():
    raw = subprocess.check_output(['lsblk', '-J', '-b', '-o',
        'NAME,PATH,TYPE,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINTS,RO,TRAN'], text=True)
    result = []
    def flatten(node):
        yield node
        for child in node.get('children', []):
            yield from flatten(child)
    for disk in json.loads(raw)['blockdevices']:
        if disk['type'] != 'disk' or disk['name'].startswith(('zram', 'ram')):
            continue
        nodes = list(flatten(disk))
        system = any('/' in (n.get('mountpoints') or []) for n in nodes)
        for node in nodes:
            if node['type'] not in ('disk', 'part'):
                continue
            if node.get('children') and not node.get('fstype'):
                continue
            fs = node.get('fstype')
            if fs == 'swap':
                continue
            mounts = [m for m in node.get('mountpoints', []) if m and m != '[SWAP]']
            mount = mounts[0] if mounts else None
            reserved = len(nodes) > 2 and node['size'] < 2 * 1024 ** 3 and fs in ('vfat', 'ntfs')
            usable = bool(fs) and not system and not node.get('ro') and not reserved
            reason = ('Disco di sistema: protetto' if system else
                      'Disco in sola lettura' if node.get('ro') else
                      'Partizione di avvio o ripristino: protetta' if reserved else
                      'Disco senza filesystem: preparalo prima dell’uso' if not fs else '')
            free = total = None
            if mount and usable:
                try:
                    usage = shutil.disk_usage(mount)
                    free, total = usage.free, usage.total
                except OSError:
                    usable, reason = False, 'Disco non disponibile'
            identity = node.get('uuid') or node['path']
            result.append({'id': hashlib.sha256(identity.encode()).hexdigest()[:24],
                'device': node['path'], 'name': node.get('label') or disk['name'],
                'size': node['size'], 'filesystem': fs, 'mount': mount,
                'available': usable, 'reason': reason, 'free': free, 'total': total,
                'transport': disk.get('tran'), 'system': system})
    return result


def find_volume(disk_id):
    for volume in physical_volumes():
        if volume['id'] == disk_id:
            if not volume['available']:
                raise ValueError(volume['reason'])
            return volume
    raise ValueError('Disco scollegato. Ricollegalo e riprova.')


def archive_root(disk_id):
    volume = find_volume(disk_id)
    if not volume['mount']:
        raise ValueError('Seleziona e collega il disco prima di trasferire.')
    # Every operation rechecks the live mount; never fall back to a local folder.
    mount = Path(volume['mount']).resolve()
    if not mount.is_mount():
        raise ValueError('Il disco non è più montato.')
    root = mount / 'PiDrive'
    if root.is_symlink():
        raise ValueError('La cartella PiDrive non può essere un collegamento.')
    root.mkdir(exist_ok=True)
    return root
