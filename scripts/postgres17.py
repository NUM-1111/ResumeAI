"""Pinned, labelled project database. Never start legacy PostgreSQL12."""
import json
import subprocess
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
LOCK=ROOT/'postgres17.lock.json'
NAME='agentcv-postgres17'
VOLUME='agentcv-postgres17-data'

def docker(*args, **kwargs):
    return subprocess.run(['docker',*map(str,args)],check=True,**kwargs)

def inspect():
    result=subprocess.run(['docker','inspect',NAME],capture_output=True,text=True)
    if result.returncode:
        if 'No such' in result.stderr:return None
        raise RuntimeError('Docker inspect failed: '+result.stderr)
    value=json.loads(result.stdout)[0]
    lock=json.loads(LOCK.read_text())
    if value['Config']['Labels'].get('com.agentcv.project')!=str(ROOT) or value['Config']['Image']!=lock['image']:
        raise RuntimeError('Container identity/image mismatch; refusing to manage')
    return value

def sql(statement,database='agentcv_validation'):
    return docker('exec','-i',NAME,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',database,
                  input=statement,text=True,capture_output=True).stdout

def start():
    state=inspect()
    if state is None:raise RuntimeError('Database not provisioned; use approved migration procedure')
    if not state['State']['Running']:docker('start',NAME)
    for _ in range(30):
        if subprocess.run(['docker','exec',NAME,'pg_isready','-U','postgres','-d','agentcv_validation'],stdout=subprocess.DEVNULL).returncode==0:
            print('PostgreSQL17 ready at 127.0.0.1:55433');return
        time.sleep(1)
    raise RuntimeError('PostgreSQL17 did not become ready; no legacy fallback')

def stop():
    state=inspect()
    if state and state['State']['Running']:docker('stop','--timeout','30',NAME)