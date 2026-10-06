#!/usr/bin/env python3
"""Pi Drive: dependency-free, bounded-memory, resumable LAN archive server."""
import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path, PurePosixPath
import secrets
import shutil
import sqlite3
import subprocess
import threading
import time
from urllib.parse import parse_qs, quote, urlparse
from disks import physical_volumes, find_volume, archive_root

STATE = Path(os.environ.get('PIDRIVE_STATE', str(Path.home() / '.local/share/pi-drive')))
TOKEN = os.environ.get('PIDRIVE_TOKEN', '')
CHUNK = 4 * 1024 * 1024
LOCK = threading.RLock()
MAX_FILE = 2 * 1024 ** 4


def db():
    conn = sqlite3.connect(STATE / 'uploads.sqlite', timeout=30)
    conn.row_factory = sqlite3.Row
    return conn


def initialize():
    STATE.mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.execute('''CREATE TABLE IF NOT EXISTS uploads (
            id TEXT PRIMARY KEY, disk TEXT NOT NULL, path TEXT NOT NULL,
            size INTEGER NOT NULL, received INTEGER DEFAULT 0,
            sha256 TEXT, verified INTEGER DEFAULT 0, created REAL NOT NULL)''')


def safe_relative(value):
    if not isinstance(value, str) or not value or len(value) > 1500 or '\\' in value or '\x00' in value:
        raise ValueError('Nome file non valido')
    path = PurePosixPath(value)
    if path.is_absolute() or any(p in ('', '.', '..') or p.startswith('.') for p in value.split('/')):
        raise ValueError('Percorso non valido')
    if any(len(p.encode()) > 240 for p in path.parts):
        raise ValueError('Nome file troppo lungo')
    return str(path)


def safe_path(root, relative):
    target = root / safe_relative(relative)
    current = root
    for part in PurePosixPath(relative).parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('I collegamenti non sono consentiti')
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError('Percorso non valido')
    return target


def row(upload_id):
    with db() as conn:
        item = conn.execute('SELECT * FROM uploads WHERE id=?', (upload_id,)).fetchone()
    if item is None:
        raise ValueError('Trasferimento non trovato')
    return dict(item)


def partial(item):
    root = archive_root(item['disk'])
    folder = root / '.incoming'
    if folder.is_symlink():
        raise ValueError('Cartella temporanea non sicura')
    folder.mkdir(exist_ok=True)
    return folder / (item['id'] + '.part')


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify(item):
    path = safe_path(archive_root(item['disk']), item['path'])
    valid = bool(item['verified'] and path.is_file() and
                 path.stat().st_size == item['size'] and digest_file(path) == item['sha256'])
    return {'id': item['id'], 'disk': item['disk'], 'path': item['path'],
            'size': item['size'], 'sha256': item['sha256'], 'verified': valid}


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        # Never log authorization headers or download tokens.
        print(f'{self.client_address[0]} {self.command} {urlparse(self.path).path}', flush=True)

    def reply(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(data)

    def json_body(self):
        count = int(self.headers.get('Content-Length', '0'))
        if not 0 < count <= 65536:
            raise ValueError('Richiesta non valida')
        return json.loads(self.rfile.read(count))

    def authorized(self):
        supplied = self.headers.get('Authorization', '')
        return bool(TOKEN and hmac.compare_digest(supplied, 'Bearer ' + TOKEN))

    def route(self):
        self.connection.settimeout(120)
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        if path == '/health' and self.command == 'GET':
            return self.reply(200, {'app': 'Pi Drive', 'version': '1.0.0'})
        if not self.authorized():
            self.close_connection = True
            return self.reply(401, {'error': 'Codice di accesso non corretto'})
        if self.command == 'GET' and path == '/api/disks':
            return self.reply(200, {'disks': physical_volumes()})
        if self.command == 'POST' and path == '/api/mount':
            disk_id = self.json_body()['disk']
            find_volume(disk_id)
            subprocess.run(['sudo', '-n', '/usr/bin/python3',
                            '/usr/local/lib/pi-drive/mount_drive.py', disk_id],
                           check=True, timeout=40, capture_output=True)
            return self.reply(200, find_volume(disk_id))
        if self.command == 'GET' and path == '/api/files':
            root = archive_root(query['disk'][0])
            rel = query.get('path', [''])[0]
            folder = safe_path(root, rel) if rel else root
            entries = []
            for child in sorted(folder.iterdir(), key=lambda p: (not p.is_dir(), p.name.casefold())):
                if child.name.startswith('.') or child.is_symlink():
                    continue
                stat = child.stat()
                entries.append({'name': child.name, 'path': str(child.relative_to(root)),
                    'directory': child.is_dir(), 'size': stat.st_size, 'modified': stat.st_mtime})
                if len(entries) >= 5000:
                    break
            return self.reply(200, {'files': entries, 'path': rel})
        if self.command == 'GET' and path == '/api/download':
            target = safe_path(archive_root(query['disk'][0]), query['path'][0])
            if not target.is_file():
                raise ValueError('File non trovato')
            with target.open('rb') as stream:
                self.send_response(200)
                self.send_header('Content-Type', mimetypes.guess_type(target.name)[0] or 'application/octet-stream')
                self.send_header('Content-Length', str(target.stat().st_size))
                self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + quote(target.name))
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.end_headers()
                shutil.copyfileobj(stream, self.wfile, 1024 * 1024)
            return
        if self.command == 'POST' and path == '/api/uploads':
            body = self.json_body()
            root = archive_root(body['disk'])
            relative = safe_relative(body['path'])
            size = int(body['size'])
            if not 0 <= size <= MAX_FILE:
                raise ValueError('Dimensione file non valida')
            if shutil.disk_usage(root).free < 2 * size + 32 * 1024 * 1024:
                return self.reply(507, {'error': 'Spazio insufficiente sul disco'})
            with LOCK:
                target = safe_path(root, relative)
                if target.exists():
                    p = PurePosixPath(relative)
                    relative = str(p.with_name(p.stem + '-' + secrets.token_hex(4) + p.suffix))
                upload_id = secrets.token_hex(16)
                with db() as conn:
                    conn.execute('INSERT INTO uploads(id,disk,path,size,created) VALUES(?,?,?,?,?)',
                                 (upload_id, body['disk'], relative, size, time.time()))
                partial(row(upload_id)).touch(exist_ok=False)
            return self.reply(201, row(upload_id))
        if path.startswith('/api/uploads/'):
            parts = path.split('/')
            upload_id = parts[3]
            with LOCK:
                item = row(upload_id)
                if self.command == 'GET' and len(parts) == 4:
                    archive_root(item['disk'])
                    if not item['verified']:
                        item['received'] = partial(item).stat().st_size
                    return self.reply(200, item)
                if self.command == 'GET' and parts[-1] == 'verify':
                    return self.reply(200, verify(item))
                if self.command == 'PUT' and len(parts) == 4:
                    if item['verified']:
                        raise ValueError('File già completato')
                    count = int(self.headers.get('Content-Length', '0'))
                    offset = int(self.headers.get('Upload-Offset', '-1'))
                    if not 0 < count <= CHUNK:
                        raise ValueError('Blocco non valido')
                    temp = partial(item)
                    actual = temp.stat().st_size
                    if offset != actual:
                        self.close_connection = True
                        return self.reply(409, {'error': 'Riprendi dal blocco corretto', 'received': actual})
                    if actual + count > item['size']:
                        raise ValueError('Il file supera la dimensione prevista')
                    expected = self.headers.get('Chunk-SHA256', '')
                    data = self.rfile.read(count)
                    if len(data) != count or not hmac.compare_digest(hashlib.sha256(data).hexdigest(), expected):
                        raise ValueError('Blocco incompleto o danneggiato: riprova')
                    if shutil.disk_usage(temp.parent).free < count + 16 * 1024 * 1024:
                        return self.reply(507, {'error': 'Disco pieno'})
                    with temp.open('ab') as stream:
                        stream.write(data)
                        stream.flush()
                        os.fsync(stream.fileno())
                    with db() as conn:
                        conn.execute('UPDATE uploads SET received=? WHERE id=?', (actual + count, upload_id))
                    return self.reply(200, {'received': actual + count})
                if self.command == 'POST' and parts[-1] == 'complete':
                    body = self.json_body()
                    if item['verified']:
                        return self.reply(200, verify(item))
                    temp = partial(item)
                    if temp.stat().st_size != item['size']:
                        raise ValueError('Trasferimento incompleto')
                    digest = digest_file(temp)
                    if not hmac.compare_digest(digest, str(body['sha256'])):
                        raise ValueError('Verifica fallita: il file originale deve restare sul telefono')
                    root = archive_root(item['disk'])
                    target = safe_path(root, item['path'])
                    target.parent.mkdir(parents=True, exist_ok=True)
                    # Exclusive copy avoids overwriting pre-existing files, even on FAT/exFAT.
                    try:
                        with target.open('xb') as out, temp.open('rb') as source:
                            shutil.copyfileobj(source, out, 1024 * 1024)
                            out.flush()
                            os.fsync(out.fileno())
                        if digest_file(target) != digest:
                            raise ValueError('La verifica del disco non è riuscita')
                    except FileExistsError:
                        raise ValueError('Il file esiste già: avvia una nuova copia')
                    except Exception:
                        target.unlink(missing_ok=True)
                        raise
                    # Persist the directory entry before issuing a deletion-safe receipt.
                    folder_fd = os.open(str(target.parent), os.O_RDONLY)
                    try:
                        os.fsync(folder_fd)
                    finally:
                        os.close(folder_fd)
                    temp.unlink()
                    with db() as conn:
                        conn.execute('UPDATE uploads SET sha256=?,verified=1 WHERE id=?', (digest, upload_id))
                    return self.reply(200, verify(row(upload_id)))
        self.reply(404, {'error': 'Risorsa non trovata'})

    def handle_request(self):
        try:
            self.route()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except (ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
            self.close_connection = True
            message = str(exc) if isinstance(exc, ValueError) else 'Operazione non riuscita. Controlla il disco e riprova.'
            self.reply(400, {'error': message})
        except Exception:
            self.close_connection = True
            self.reply(500, {'error': 'Errore temporaneo. Nessun originale è stato cancellato.'})

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8090)
    args = parser.parse_args()
    if len(TOKEN) < 16:
        raise SystemExit('Set PIDRIVE_TOKEN to a secret of at least 16 characters')
    initialize()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
