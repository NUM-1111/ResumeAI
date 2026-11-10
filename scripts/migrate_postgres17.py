"""One-off approved logical migration, no overwrite or legacy deletion."""
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import time
from datetime import datetime
from local import ROOT, LOCAL, config, stop_application
from postgres17 import docker, sql, NAME, VOLUME, LOCK

if LOCK.exists():raise SystemExit('Migration lock already exists; inspect before retrying')
image=json.loads(subprocess.check_output(['docker','image','inspect','postgres:17-bookworm']))[0]
digest=next(x for x in image['RepoDigests'] if x.startswith('postgres@sha256:'))
for args in (['container','inspect',NAME],['volume','inspect',VOLUME]):
    if subprocess.run(['docker',*args],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        raise SystemExit('Target exists; refusing to overwrite')
stop_application()
backup=LOCAL/('migration17-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir(mode=0o700)
values=config()
envfile=backup/'container.env'
envfile.write_text('POSTGRES_PASSWORD='+values['admin']+'\n')
envfile.chmod(0o600)
docker('volume','create','--label','com.agentcv.project='+str(ROOT),VOLUME)
docker('run','-d','--name',NAME,'--label','com.agentcv.project='+str(ROOT),
       '--restart','no','--publish','127.0.0.1:55433:5432','--mount','source='+VOLUME+',target=/var/lib/postgresql/data',
       '--env-file',envfile,digest)
for _ in range(40):
    if subprocess.run(['docker','exec',NAME,'pg_isready','-U','postgres'],stdout=subprocess.DEVNULL).returncode==0:break
    time.sleep(1)
else:raise RuntimeError('PostgreSQL17 not ready; originals preserved')
version=sql('SHOW server_version;','postgres')
if '17.' not in version:raise RuntimeError('Unexpected major version')
sql("CREATE ROLE agentcv_validation LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+values['database']+"';\nCREATE DATABASE agentcv_validation OWNER agentcv_validation;",'postgres')
# Legacy binds loopback only: dump with installed12 client, supported logical format into17.
dump=backup/'database.dump'
with dump.open('wb') as out:
    subprocess.run(['/usr/sbin/runuser','-u','postgres','--','/usr/lib/postgresql/12/bin/pg_dump','-h','/var/lib/postgresql/resumeai-validation','-p','55432','-d','agentcv_validation','-Fc','--no-owner','--no-acl'],stdout=out,check=True)
dump.chmod(0o600)
materials=LOCAL/'materials'
with tarfile.open(backup/'materials.tar.gz','w:gz') as archive:
    if materials.exists():archive.add(materials,arcname='materials')
(backup/'materials.tar.gz').chmod(0o600)
with dump.open('rb') as source:
    docker('exec','-i',NAME,'pg_restore','-U','postgres','-d','agentcv_validation','--no-owner','--no-acl','--role=agentcv_validation','--exit-on-error',stdin=source)
check="SELECT 'profile',count(*) FROM profile UNION ALL SELECT 'workspace',count(*) FROM workspace UNION ALL SELECT 'material',count(*) FROM material_import UNION ALL SELECT 'revision',count(*) FROM resume_revision UNION ALL SELECT 'recycle',count(*) FROM recycle_entry ORDER BY 1;"
old=subprocess.check_output(['/usr/sbin/runuser','-u','postgres','--','/usr/lib/postgresql/12/bin/psql','-X','-h','/var/lib/postgresql/resumeai-validation','-p','55432','-d','agentcv_validation','-c',check],text=True)
new=sql(check)
if old!=new:raise RuntimeError('Row count comparison differs; no switch performed')
manifest={'image':digest,'version':version.strip(),'backup':str(backup),'dumpSha256':hashlib.sha256(dump.read_bytes()).hexdigest(),'rowCounts':new}
(backup/'manifest.json').write_text(json.dumps(manifest,indent=2))
LOCK.write_text(json.dumps({'image':digest,'version':version.strip()},indent=2)+'\n')
print('Migrated to pinned PostgreSQL17. Backup: '+str(backup))