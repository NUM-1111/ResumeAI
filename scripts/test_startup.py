"""Exercise the foreground launcher, then stop only the process group we own."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import urllib.request

from local import ROOT, LOCAL, listening

if listening(8080):
    raise SystemExit('Port 8080 occupied; refusing to reuse or stop another process')
with (LOCAL / 'launcher-test.log').open('a') as log:
    child = subprocess.Popen([sys.executable, str(ROOT / 'scripts/local.py'), 'run'],
                             cwd=str(ROOT), stdout=log, stderr=log, start_new_session=True)
    try:
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(30):
            if child.poll() is not None:
                raise RuntimeError('Launcher exited; inspect launcher-test.log')
            try:
                with client.open('http://127.0.0.1:8080/login', timeout=2) as response:
                    assert response.status == 200
                    assert b'password' in response.read()
                print('PASS: foreground launcher serves actual login page', flush=True)
                # Leave time for the independent Windows host HTTP verification.
                time.sleep(15)
                break
            except OSError:
                time.sleep(1)
        else:
            raise RuntimeError('Login readiness timed out')
    finally:
        os.killpg(child.pid, signal.SIGINT)
        try:
            child.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGTERM)
            child.wait(timeout=10)
        print('Owned launcher/process group stopped; no unrelated process touched.', flush=True)