"""Explicit isolated real-JAR restart checks. No model key, no personal data, no app process stopped."""
import json
from pathlib import Path
import socket
import subprocess
import time
import uuid
import urllib.request

import skeleton
from passwords import test_credentials

ROOT = skeleton.ROOT
PORT = 18088


def sql(statement):
    result=subprocess.run(['docker','exec','-i','agentcv-m00-postgres','psql','-X','-t','-A','-v','ON_ERROR_STOP=1',
                           '-U','agentcv_m00','-d','agentcv_m00'],input=statement,text=True,capture_output=True,check=True)
    return result.stdout.strip()


def execute():
    skeleton.m00_database.prepare()
    schema='m08_'+uuid.uuid4().hex[:12]
    folder=ROOT/'.local'/schema
    folder.mkdir(mode=0o700)
    env,_=test_credentials(skeleton.environment())
    env.pop('DEEPSEEK_API_KEY',None)
    env['AGENTCV_DISABLE_AI_WORKER']='true'
    env['SPRING_DATASOURCE_URL']='jdbc:postgresql://127.0.0.1:55434/agentcv_m00?currentSchema='+schema
    env['SPRING_FLYWAY_DEFAULT_SCHEMA']=schema
    sql('CREATE SCHEMA '+schema+' AUTHORIZATION agentcv_m00_app;')
    processes=[]
    def start(name,worker):
        runtime=env.copy();runtime['AGENTCV_DISABLE_AI_WORKER']='false' if worker else 'true'
        with (folder/(name+'.log')).open('w') as log:
            process=subprocess.Popen(['java','-jar',str(skeleton.JAR),'--server.port='+str(PORT),
                                      '--agentcv.ai.worker-lock='+str(folder/'worker.lock'),
                                      '--agentcv.files.root='+str(folder/'files')],cwd=ROOT,env=runtime,stdout=log,stderr=subprocess.STDOUT)
        processes.append(process)
        return process
    def ready(process):
        for _ in range(100):
            if process.poll() is not None:raise RuntimeError('isolated application failed; inspect local m08 log')
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(PORT)+'/api/v1/system/status',timeout=1):return
            except OSError:time.sleep(.1)
        raise RuntimeError('isolated startup timeout')
    def stop(process,crash=False):
        if process.poll() is None:
            process.kill() if crash else process.terminate()
            process.wait(timeout=10)
    try:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',PORT))==0:raise RuntimeError('test port occupied; no process stopped')
        initial=start('initialize',False);ready(initial);stop(initial)
        p=uuid.uuid4();w1,w2,w3=[uuid.uuid4() for _ in range(3)];r1,r2,r3=[uuid.uuid4() for _ in range(3)]
        statements=["SET search_path="+schema+";",f"INSERT INTO profile(id,display_name) VALUES ('{p}','M08合成恢复');"]
        for w in (w1,w2,w3):statements.append(f"INSERT INTO workspace(id,profile_id,name,direction) VALUES ('{w}','{p}','恢复验证','Java');")
        for r,w,execution,record in ((r1,w1,'ACCEPTED','PENDING'),(r2,w2,'CALLING','PENDING'),(r3,w3,'CALLING','SAVE_FAILED')):
            statements.append(f"INSERT INTO ai_request(id,profile_id,workspace_id,request_key,payload_hash,message,input_json,model,execution_status,record_status) VALUES ('{r}','{p}','{w}','{uuid.uuid4()}','synthetic','合成恢复问题','{{}}','deepseek-flash','{execution}','{record}');")
        sql('\n'.join(statements))
        worker=start('recovery',True);ready(worker)
        rows=json.loads(sql('SET search_path='+schema+f"; SELECT json_agg(json_build_object('id',id,'execution',execution_status,'record',record_status,'error',error_code,'snapshot_empty',input_json='')) FROM ai_request; ").splitlines()[-1])
        states={row['id']:row for row in rows}
        assert states[str(r1)]['execution']=='MODEL_FAILED' and states[str(r1)]['error']=='MODEL_NOT_CONFIGURED'
        assert states[str(r2)]['execution']=='UNKNOWN' and states[str(r2)]['snapshot_empty']
        assert states[str(r3)]['error']=='RESULT_LOST_ON_RESTART' and states[str(r3)]['record']=='SAVED'
        # Crash the worker after recovering a new CALLING row; next startup must conservatively mark it unknown.
        sql('SET search_path='+schema+f"; UPDATE ai_request SET execution_status='CALLING',record_status='PENDING',error_code=null WHERE id='{r2}';")
        stop(worker,True)
        restarted=start('after-crash',True);ready(restarted)
        assert sql('SET search_path='+schema+f"; SELECT execution_status FROM ai_request WHERE id='{r2}';").splitlines()[-1]=='UNKNOWN'
        stop(restarted)
        result={'status':'PASS','real_model_calls':0,'schema':schema,'accepted_recovered_to_unconfigured_failure':True,
                'calling_not_redispatched':True,'lost_buffer_unblocked':True,'forced_process_exit_lock_released':True}
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print(json.dumps(result,ensure_ascii=False))
    finally:
        for process in processes:stop(process)
        # Only this invocation's freshly generated synthetic schema. Logs remain as evidence.
        sql('DROP SCHEMA '+schema+' CASCADE;')


if __name__=='__main__':
    execute()