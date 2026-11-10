"""Bounded official image pull; no daemon/global proxy configuration changes."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
with (root / '.local/postgres17-pull.log').open('a') as log:
    try:
        result = subprocess.run(['docker', 'pull', 'postgres:17-bookworm'],
                                stdout=log, stderr=log, timeout=120)
        log.write('\nExit code: ' + str(result.returncode) + '\n')
    except subprocess.TimeoutExpired:
        log.write('\nPull exceeded 120 seconds; client stopped, no configuration changed.\n')