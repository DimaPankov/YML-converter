import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import desktop
import server


class DesktopUnitTests(unittest.TestCase):
    def test_data_is_outside_installation(self):
        with patch.dict(os.environ, {'LOCALAPPDATA': '/tmp/profile'}):
            self.assertEqual(desktop.user_data_dir(), Path('/tmp/profile/YMLStudio/data'))
            self.assertNotEqual(desktop.user_data_dir(),server.ROOT/'data')

    def test_stale_session_is_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            (root/desktop.SESSION_NAME).write_text('{broken json','utf-8')
            self.assertIsNone(desktop.load_session(root))
            (root/desktop.SESSION_NAME).write_text(json.dumps({'port':-1,'token':'abc'}),'utf-8')
            self.assertIsNone(desktop.load_session(root))

    def test_default_browser_fallback(self):
        with patch.object(Path,'is_file',return_value=False),patch.object(desktop.webbrowser,'open',return_value=True) as opener:
            desktop.open_interface('http://127.0.0.1:1234/',Path('/tmp/profile'))
            opener.assert_called_once_with('http://127.0.0.1:1234/')

    def test_stop_without_running_app(self):
        with tempfile.TemporaryDirectory() as d:
            desktop.stop_existing(Path(d))

    def test_payload_runtime_and_paths(self):
        payload=server.ROOT/'build/windows/payload'
        if not payload.exists(): self.skipTest('Build payload not present')
        self.assertEqual((payload/'runtime/python313._pth').read_text(),'python313.zip\n.\n..\n')
        for name in ('pythonw.exe','python.exe','python313.dll','python313.zip','_ctypes.pyd','_socket.pyd','_ssl.pyd','LICENSE.txt'):
            self.assertTrue((payload/'runtime'/name).is_file(),name)
        self.assertFalse((payload/'data').exists())
        self.assertTrue((payload/'static/desktop.js').is_file())


class DesktopHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.data_patch=patch.object(server,'DATA',Path(self.temp.name))
        self.data_patch.start()
        (server.DATA/'images').mkdir()
        self.httpd=desktop.DesktopServer('test-secret')
        self.worker=threading.Thread(target=self.httpd.serve_forever,daemon=True)
        self.worker.start()
        self.session={'port':self.httpd.server_port,'token':'test-secret'}
        self.base='http://127.0.0.1:'+str(self.httpd.server_port)
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def tearDown(self):
        self.httpd.shutdown();self.httpd.server_close();self.worker.join(timeout=3)
        self.data_patch.stop();self.temp.cleanup()

    def test_heartbeat_and_authentication(self):
        before=self.httpd.last_seen
        self.assertEqual(desktop.control_request(self.session)['application'],'YMLStudio')
        self.assertGreater(self.httpd.last_seen,before)
        with self.assertRaises(urllib.error.HTTPError) as caught:
            desktop.control_request(dict(self.session,token='wrong'))
        self.assertEqual(caught.exception.code,403)

    def test_wrong_origin_rejected(self):
        req=urllib.request.Request(self.base+'/api/desktop/stop',data=b'{}',headers={'X-Desktop-Token':'test-secret','Origin':'https://unrelated.example'})
        with self.assertRaises(urllib.error.HTTPError) as caught: self.opener.open(req)
        self.assertEqual(caught.exception.code,403)
        self.assertFalse(self.httpd.stopping.is_set())

    def test_assets_and_api_available(self):
        for path in ('/','/app.js','/desktop.js','/style.css','/api/state'):
            with self.opener.open(self.base+path) as response: self.assertEqual(response.status,200)
        with self.opener.open(self.base+'/api/desktop') as response:
            self.assertTrue(json.load(response)['enabled'])

    def test_stop_terminates_server(self):
        desktop.control_request(self.session,'stop')
        self.assertTrue(self.httpd.stopping.wait(2))
        self.worker.join(timeout=3)
        self.assertFalse(self.worker.is_alive())


if __name__=='__main__': unittest.main()
