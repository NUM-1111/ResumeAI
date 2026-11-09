import json
import os
from pathlib import Path
import subprocess
import urllib.parse
import xml.etree.ElementTree as ET

def host(value):
    parsed = urllib.parse.urlsplit(value)
    return {'scheme': parsed.scheme, 'host': parsed.hostname, 'port': parsed.port}

print('Proxy endpoints (credentials excluded):')
for key in ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY'):
    if os.environ.get(key):
        print(key, host(os.environ[key]))
path = Path('/root/.m2/settings.xml')
if path.exists():
    root = ET.parse(str(path)).getroot()
    for node in root.iter():
        if node.tag.split('}')[-1] == 'url' and node.text:
            print('Maven configured URL:', host(node.text))
for path in (Path('/root/.npmrc'), Path('/root/java/ResumeAI/frontend/.npmrc')):
    if path.exists():
        for line in path.read_text().splitlines():
            if line.startswith(('registry=', 'proxy=', 'https-proxy=')):
                key, value = line.split('=', 1)
                print('npm', key, host(value))
print('Checkpoint processes:')
result = subprocess.run(['ps', '-eo', 'pid,ppid,etimes,args'], text=True, stdout=subprocess.PIPE, check=True)
for line in result.stdout.splitlines():
    if any(x in line for x in ('scripts/local.py', 'maven', 'npm install', 'npm ci')):
        print(line)