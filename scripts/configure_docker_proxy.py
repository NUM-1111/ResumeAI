"""User-approved Docker proxy configuration, backup first, no secret output."""
import json
import os
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

path = Path('/etc/docker/daemon.json')
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if not proxy or urlsplit(proxy).scheme != 'http':
    raise SystemExit('An explicit HTTP CONNECT proxy is required')
if subprocess.check_output(['docker','ps','-q']).strip():
    raise SystemExit('Running containers detected; refusing restart')
backup = path.with_name('daemon.json.agentcv-backup-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
shutil.copy2(path, backup)
config = json.loads(path.read_text())
config['registry-mirrors'] = []
config['proxies'] = {'http-proxy':proxy, 'https-proxy':proxy, 'no-proxy':'localhost,127.0.0.1,::1'}
try:
    path.write_text(json.dumps(config, indent=2)+'\n')
    subprocess.run(['dockerd','--validate','--config-file',str(path)], check=True)
    subprocess.run(['systemctl','restart','docker'], check=True)
    subprocess.run(['docker','info','--format','Docker server {{.ServerVersion}} ready'],check=True)
except Exception:
    shutil.copy2(backup,path)
    subprocess.run(['systemctl','restart','docker'],check=False)
    raise
print('Original configuration preserved at '+str(backup))