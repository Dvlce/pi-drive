import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import app
import disks


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.root = base / 'drive'
        self.root.mkdir()
        app.STATE = base / 'state'
        app.TOKEN = 'test-token-not-for-production'
        app.initialize()
        self.mount = patch.object(app, 'archive_root', return_value=self.root)
        self.mount.start()
        self.server = app.ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.mount.stop()
        self.temp.cleanup()

    def request(self, method, path, data=None, headers=None, authenticated=True):
        headers = dict(headers or {})
        if authenticated:
            headers['Authorization'] = 'Bearer ' + app.TOKEN
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        connection.request(method, path, data, headers)
        response = connection.getresponse()
        status, body = response.status, response.read()
        connection.close()
        return status, json.loads(body)

    def upload(self, content=b'Una foto importante', path='Telefono/foto.jpg'):
        status, item = self.request('POST', '/api/uploads', {'disk': 'test', 'path': path, 'size': len(content)})
        self.assertEqual(status, 201)
        endpoint = '/api/uploads/' + item['id']
        split = len(content) // 2
        for offset, chunk in [(0, content[:split]), (split, content[split:])]:
            status, _ = self.request('PUT', endpoint, chunk, {
                'Upload-Offset': str(offset), 'Chunk-SHA256': hashlib.sha256(chunk).hexdigest()})
            self.assertEqual(status, 200)
        status, receipt = self.request('POST', endpoint + '/complete', {'sha256': hashlib.sha256(content).hexdigest()})
        self.assertEqual(status, 200)
        self.assertTrue(receipt['verified'])
        return endpoint, receipt

    def test_verified_copy_and_corruption_prevent_cleanup(self):
        endpoint, receipt = self.upload()
        self.assertEqual((self.root / receipt['path']).read_bytes(), b'Una foto importante')
        self.assertTrue(self.request('GET', endpoint + '/verify')[1]['verified'])
        (self.root / receipt['path']).write_bytes(b'Una foto danneggiat')
        self.assertFalse(self.request('GET', endpoint + '/verify')[1]['verified'])

    def test_unauthenticated_and_traversal_are_rejected(self):
        self.assertEqual(self.request('GET', '/api/disks', authenticated=False)[0], 401)
        for path in ['../secrets', '/etc/passwd', 'foo/../../x', '.incoming/attack', 'foo\\bar']:
            self.assertEqual(self.request('POST', '/api/uploads', {'disk':'test', 'path':path, 'size':3})[0], 400)

    def test_retry_bad_chunk_and_resume(self):
        _, item = self.request('POST', '/api/uploads', {'disk':'test', 'path':'a.txt', 'size':3})
        endpoint = '/api/uploads/' + item['id']
        self.assertEqual(self.request('PUT', endpoint, b'abc', {'Upload-Offset':'0', 'Chunk-SHA256':'bad'})[0], 400)
        self.assertEqual(self.request('GET', endpoint)[1]['received'], 0)
        header = {'Upload-Offset':'0', 'Chunk-SHA256':hashlib.sha256(b'abc').hexdigest()}
        self.assertEqual(self.request('PUT', endpoint, b'abc', header)[0], 200)
        self.assertEqual(self.request('PUT', endpoint, b'abc', header)[0], 409)
        self.assertEqual(self.request('GET', endpoint)[1]['received'], 3)
        self.assertEqual(self.request('POST', endpoint+'/complete', {'sha256':'bad'})[0], 400)

    def test_existing_file_preserved_and_symlinks_rejected(self):
        (self.root / 'file.jpg').write_bytes(b'originale')
        _, receipt = self.upload(path='file.jpg')
        self.assertNotEqual(receipt['path'], 'file.jpg')
        self.assertEqual((self.root / 'file.jpg').read_bytes(), b'originale')
        (self.root / 'link').symlink_to(Path(self.temp.name))
        self.assertEqual(self.request('POST', '/api/uploads', {'disk':'test','path':'link/x','size':1})[0], 400)

    def test_unplugged_drive_never_falls_back_to_system(self):
        endpoint, _ = self.upload()
        with patch.object(app, 'archive_root', side_effect=ValueError('Disco scollegato')):
            self.assertEqual(self.request('GET', endpoint+'/verify')[0], 400)

    def test_system_disk_is_not_a_destination(self):
        fake = {'blockdevices':[{'type':'disk','name':'nvme0n1','path':'/dev/nvme0n1',
            'size':1000,'fstype':None,'children':[{'type':'part','name':'nvme0n1p2',
            'path':'/dev/nvme0n1p2','size':900,'fstype':'ext4','uuid':'root',
            'mountpoints':['/']}]}, {'type':'disk','name':'sda','path':'/dev/sda',
            'size':2000,'fstype':'exfat','uuid':'usb','mountpoints':[],'tran':'usb'}]}
        with patch.object(disks.subprocess, 'check_output', return_value=json.dumps(fake)):
            volumes = disks.physical_volumes()
        self.assertFalse(volumes[0]['available'])
        self.assertTrue(volumes[1]['available'])


if __name__ == '__main__':
    unittest.main()
