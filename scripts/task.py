"""Run bounded checkpoint jobs without transport timeouts; inspect .local logs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('name', choices=['frontend', 'verify', 'dependencies', 'run', 'smoke', 'startup-test', 'browser-s01'])
args = parser.parse_args()
local = ROOT / '.local'
local.mkdir(mode=0o700, exist_ok=True)
pid_file = local / (args.name + '.pid')
if pid_file.exists():
    pid = int(pid_file.read_text())
    try:
        os.kill(pid, 0)
        if Path('/proc/' + str(pid) + '/stat').read_text().split()[2] != 'Z':
            raise SystemExit('Task already exists; inspect its log before retrying')
    except ProcessLookupError:
        pass
with (local / (args.name + '.log')).open('a') as log:
    log.write('\n--- checkpoint task start ---\n')
    log.flush()
    command = [sys.executable, str(ROOT / 'scripts/smoke.py')] if args.name == 'smoke' else [sys.executable, str(ROOT / 'scripts/local.py'), args.name]
    if args.name == 'startup-test':
        command = [sys.executable, str(ROOT / 'scripts/test_startup.py')]
    if args.name == 'browser-s01':
        command = [sys.executable, str(ROOT / 'scripts/browser_s01.py')]
    process = subprocess.Popen(command,
                               cwd=str(ROOT), stdout=log, stderr=log, start_new_session=True)
pid_file.write_text(str(process.pid))
print(json.dumps({'task': args.name, 'pid': process.pid, 'log': str(local / (args.name + '.log'))}))