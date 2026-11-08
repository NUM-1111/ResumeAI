"""Initialize only this project's Git baseline, audit before staging/commit."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
import argparse
parser = argparse.ArgumentParser()
parser.add_argument('--message', default='chore: establish verified environment baseline (AI-assisted)')
message = parser.parse_args().message
def git(*args):
    return subprocess.run(['git', '-C', str(ROOT), *args], check=True, stdout=subprocess.PIPE, text=True).stdout

if not (ROOT / '.git').exists():
    git('init')
    git('symbolic-ref', 'HEAD', 'refs/heads/main')
paths = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').strip('\0').split('\0')
secret_path = ROOT / '.local/credentials.json'
secrets = json.loads(secret_path.read_text()).values() if secret_path.exists() else []
for relative in paths:
    if any(part in ('.local', 'node_modules', 'target', 'dist', '.test-build', '__pycache__') for part in Path(relative).parts):
        raise SystemExit('Refusing generated/private path: ' + relative)
    content = (ROOT / relative).read_bytes()
    if any(value.encode() in content for value in secrets):
        raise SystemExit('Refusing credential match; file path only: ' + relative)
git('add', '--all')
print('Audited staged files:', len(git('ls-files').splitlines()))
git('-c', 'user.name=AgentCV Coding Assistant', '-c', 'user.email=agentcv-assistant@localhost',
    'commit', '-m', message)
print(git('rev-parse', 'HEAD'))