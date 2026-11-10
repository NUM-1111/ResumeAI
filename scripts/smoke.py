"""Real packaged-JAR HTTP checks; no mocked server and no external services."""
import http.cookiejar
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

from local import ROOT, LOCAL, config, environment, listening
from passwords import test_credentials

BASE = 'http://127.0.0.1:8080'
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.ProxyHandler({}))


def request(path, data=None, headers=None):
    req = urllib.request.Request(BASE + path, data=data, headers=headers or {})
    try:
        with client.open(req, timeout=5) as response:
            return response.status, response.read(), response.geturl(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.geturl(), error.headers


def expect(condition, label):
    if not condition:
        raise AssertionError(label)
    print('PASS: ' + label, flush=True)


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--password-length', type=int, default=24, choices=range(6,33))
    options = parser.parse_args()
    if not 1024 <= options.port <= 65535:
        raise RuntimeError('Smoke port must be 1024 to 65535')
    BASE = 'http://127.0.0.1:' + str(options.port)
    if listening(options.port):
        raise RuntimeError('Smoke port already has a listener; refusing to reuse it')
    # Never stop or reuse an unrelated process already listening on the target port.
    import socket
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', options.port))
    log_path = LOCAL / 'jar-smoke.log'
    with log_path.open('a') as log:
        env, password = test_credentials(environment(), options.password_length)
        env['SERVER_PORT'] = str(options.port)
        process = subprocess.Popen(['java', '-jar', str(ROOT / 'target/agentcv-0.0.1-SNAPSHOT.jar')],
                                   cwd=str(ROOT), env=env, stdout=log, stderr=log)
        try:
            for _ in range(60):
                if process.poll() is not None:
                    raise RuntimeError('JAR exited; inspect .local/jar-smoke.log')
                try:
                    if request('/api/v1/csrf')[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(1)
            else:
                raise RuntimeError('JAR readiness timed out')
            expect(request('/api/v1/environment')[0] == 401, 'anonymous API returns 401')
            status, body, _, headers = request('/api/v1/csrf')
            token = json.loads(body)
            expect(status == 200 and headers['Cache-Control'] == 'no-store', 'CSRF response is not cached')
            raw = urllib.parse.urlencode({'username': 'operator', 'password': password}).encode()
            expect(request('/login', raw)[0] == 403, 'login without CSRF returns 403')
            wrong = urllib.parse.urlencode({'username': 'operator', 'password': 'invalid',
                                           token['parameterName']: token['token']}).encode()
            expect('error' in request('/login', wrong)[2], 'wrong credential does not authenticate')
            token = json.loads(request('/api/v1/csrf')[1])
            raw = urllib.parse.urlencode({'username': 'operator', 'password': password,
                                         token['parameterName']: token['token']}).encode()
            status, html, url, _ = request('/login', raw)
            expect(status == 200 and url == BASE + '/', 'real form login establishes session')
            assets = re.findall(r'(?:src|href)="(/assets/[^\"]+)"', html.decode())
            expect(len(assets) >= 2, 'packaged page references built JS and CSS')
            for asset in assets:
                status, body, _, _ = request(asset)
                expect(status == 200 and len(body) > 0, 'same-origin asset served: ' + asset)
            status, body, _, _ = request('/api/v1/environment')
            result = json.loads(body)
            expect(status == 200 and result['database'] == 'CONNECTED' and result['migrationVersion'] == '3'
                   and result['marker'] == '环境验证：中文持久化正常', 'real JDBC/Flyway response contains Chinese marker')
            expect(request('/logout', b'')[0] == 403, 'logout without CSRF returns 403')
            token = json.loads(request('/api/v1/csrf')[1])
            status, _, url, _ = request('/logout', b'', {token['headerName']: token['token']})
            expect(status == 200 and '/login?logout' in url, f'logout with fresh CSRF succeeds (status={status}, url={url})')
            expect(request('/api/v1/environment')[0] == 401, 'logged-out session cannot read API')
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    print('Packaged JAR stopped; isolated database remains available.', flush=True)


if __name__ == '__main__':
    main()