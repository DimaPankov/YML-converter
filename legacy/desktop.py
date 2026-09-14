"""Windows launcher for the installed application (runs with bundled pythonw.exe)."""
import argparse
import ctypes
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
import webbrowser

import server

APP_NAME = 'YML Студия'
SESSION_NAME = 'desktop-session.json'
# A closed browser does not leave a permanent background server.
IDLE_SECONDS = 600


def user_data_dir():
    base = os.environ.get('LOCALAPPDATA')
    if not base:
        raise RuntimeError('Не удалось определить папку LOCALAPPDATA пользователя Windows.')
    return Path(base) / 'YMLStudio' / 'data'


def control_request(session, action='ping'):
    port = int(session['port'])
    if not 1 <= port <= 65535:
        raise ValueError('Некорректный порт')
    request = urllib.request.Request(
        f'http://127.0.0.1:{port}/api/desktop/{action}', data=b'{}',
        headers={'Content-Type': 'application/json', 'X-Desktop-Token': session['token']})
    # Loopback control must not be sent through a user's corporate proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=2) as response:
        return json.load(response)


def load_session(data_dir):
    try:
        session = json.loads((data_dir / SESSION_NAME).read_text('utf-8'))
        if control_request(session).get('application') == 'YMLStudio':
            return session
    except (OSError, ValueError, KeyError):
        pass
    return None


def open_interface(url, data_dir):
    """Prefer a separate Edge/Chrome app window; use the default browser as fallback."""
    candidates = []
    for root in ('ProgramFiles(x86)', 'ProgramFiles', 'LOCALAPPDATA'):
        base = os.environ.get(root)
        if base:
            candidates.extend([
                Path(base) / 'Microsoft/Edge/Application/msedge.exe',
                Path(base) / 'Google/Chrome/Application/chrome.exe',
            ])
    for exe in candidates:
        if exe.is_file():
            try:
                subprocess.Popen([
                    str(exe), '--app=' + url,
                    '--user-data-dir=' + str(data_dir.parent / 'browser-profile'),
                    '--no-first-run', '--no-default-browser-check',
                    '--disable-background-mode',
                ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
            except OSError:
                continue
    if not webbrowser.open(url):
        raise RuntimeError('Не удалось открыть окно программы. Установите Microsoft Edge или Google Chrome.')


class DesktopServer(server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, token):
        self.desktop_token = token
        self.last_seen = time.monotonic()
        self.stopping = threading.Event()
        super().__init__(('127.0.0.1', 0), DesktopHandler)

    def stop(self):
        self.stopping.set()
        self.shutdown()


class DesktopHandler(server.Handler):
    def do_GET(self):
        if self.path == '/api/desktop':
            return self.send(200, {'enabled': True})
        return super().do_GET()

    def do_POST(self):
        if self.path.startswith('/api/desktop/'):
            origin = self.headers.get('Origin')
            if origin and origin != f'http://{self.headers.get("Host")}':
                return self.send(403, {'error': 'Запрос с другого сайта запрещён'})
            if not hmac.compare_digest(self.headers.get('X-Desktop-Token', ''), self.server.desktop_token):
                return self.send(403, {'error': 'Неверный ключ запуска'})
            if self.path not in ('/api/desktop/ping', '/api/desktop/stop'):
                return self.send(404, {'error': 'Не найдено'})
            self.server.last_seen = time.monotonic()
            self.send(200, {'application': 'YMLStudio', 'ok': True})
            if self.path.endswith('/stop'):
                threading.Thread(target=self.server.stop, daemon=True).start()
            return
        return super().do_POST()


def acquire_mutex(data_dir):
    """Named per-user mutex survives neither a crash nor a Windows restart."""
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    digest = hashlib.sha256(str(data_dir).lower().encode('utf-8')).hexdigest()[:24]
    handle = kernel.CreateMutexW(None, False, 'Local\\YMLStudio-' + digest)
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    return kernel, handle, ctypes.get_last_error() == 183


def stop_existing(data_dir):
    session = load_session(data_dir)
    if session:
        control_request(session, 'stop')
        # Let the original interpreter release its DLLs before installer updates them.
        for _ in range(50):
            if not (data_dir / SESSION_NAME).exists():
                time.sleep(.5)
                return
            time.sleep(.1)
        raise RuntimeError('Программа ещё работает. Закройте её и повторите установку.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stop', action='store_true')
    args = parser.parse_args()
    data_dir = user_data_dir()
    if args.stop:
        stop_existing(data_dir)
        return
    (data_dir / 'images').mkdir(parents=True, exist_ok=True)
    kernel, mutex, exists = acquire_mutex(data_dir)
    httpd = None
    worker = None
    session_file = data_dir / SESSION_NAME
    token = None
    try:
        if exists:
            for _ in range(30):
                previous = load_session(data_dir)
                if previous:
                    open_interface(f'http://127.0.0.1:{previous["port"]}/#desktop={previous["token"]}', data_dir)
                    return
                time.sleep(.1)
            raise RuntimeError('Программа уже запускается. Подождите несколько секунд и откройте ярлык снова.')
        server.DATA = data_dir
        token = secrets.token_urlsafe(32)
        httpd = DesktopServer(token)
        session = dict(port=httpd.server_port, token=token)
        session_file.write_text(json.dumps(session), 'utf-8')
        worker = threading.Thread(target=httpd.serve_forever, daemon=True)
        worker.start()
        open_interface(f'http://127.0.0.1:{httpd.server_port}/#desktop={token}', data_dir)
        last_check = time.monotonic()
        while not httpd.stopping.wait(5):
            now = time.monotonic()
            if now - last_check > 30:
                # Give an open window time to reconnect after Windows wakes up.
                httpd.last_seen = now
            last_check = now
            if now - httpd.last_seen > IDLE_SECONDS:
                httpd.stop()
        worker.join(timeout=5)
    finally:
        if httpd:
            if worker is not None and worker.is_alive():
                httpd.shutdown()
            httpd.server_close()
        if token:
            try:
                if json.loads(session_file.read_text('utf-8')).get('token') == token:
                    session_file.unlink()
            except (OSError, ValueError):
                pass
        kernel.CloseHandle(mutex)


if __name__ == '__main__':
    try:
        # pythonw has no stderr. Redirect diagnostics instead of silently losing errors.
        log_dir = user_data_dir().parent
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / 'application.log'
        if log_path.exists() and log_path.stat().st_size > 2 * 1024 * 1024:
            log_path.replace(log_dir / 'application.previous.log')
        with log_path.open('a', encoding='utf-8', buffering=1) as log:
            sys.stdout = sys.stderr = log
            try:
                main()
            except Exception:
                traceback.print_exc()
                raise
    except Exception as error:
        ctypes.windll.user32.MessageBoxW(None, str(error), APP_NAME, 0x10)
        sys.exit(1)
