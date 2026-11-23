"""Explicitly invoked, one paid synthetic request through the real browser and backend.

Never used by verify/smoke. Do not rerun automatically after an unknown outcome.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid
import urllib.request

import skeleton
from passwords import test_credentials

ROOT = skeleton.ROOT
RESULT = ROOT / '.local/m04-live-result.json'
LOG = ROOT / '.local/m04-live-test.log'
PORT = 18084


def execute():
    result = {'status': 'RUNNING', 'real_calls_submitted': 0, 'test': 'synthetic-only'}
    RESULT.write_text(json.dumps(result))
    server = None
    try:
        env, password = test_credentials(skeleton.environment())
        if not env.get('DEEPSEEK_API_KEY', '').strip():
            raise RuntimeError('MODEL_KEY_NOT_CONFIGURED')
        # Existing application owns the sole worker; temporary frontend/API uses no second worker.
        env['AGENTCV_DISABLE_AI_WORKER'] = 'true'
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', PORT)) == 0:
                raise RuntimeError('LIVE_TEST_PORT_OCCUPIED')
        # No database preparation/rebuild and no restarts of another application.
        with (ROOT / '.local/m04-live-server.log').open('w') as log:
            server = subprocess.Popen(['java', '-jar', str(skeleton.JAR), '--server.port='+str(PORT)],
                                      cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('TEST_APPLICATION_START_FAILED_CHECK_LOCAL_LOG')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:'+str(PORT)+'/api/v1/system/status', timeout=1):
                        break
                except OSError:
                    time.sleep(.2)
            else:
                raise RuntimeError('TEST_APPLICATION_START_TIMEOUT')
            from playwright.sync_api import sync_playwright, expect
            with sync_playwright() as pw:
                browser = pw.chromium.launch(headless=True, args=['--no-sandbox'])
                context = browser.new_context(viewport={'width':1440,'height':1000})
                page = context.new_page()
                page.set_default_timeout(10000)
                page.goto('http://127.0.0.1:'+str(PORT))
                page.get_by_label('口令', exact=True).fill(password)
                page.get_by_role('button',name='登录',exact=True).click()
                page.get_by_role('button',name='＋新建档案').click()
                page.get_by_label('档案名称',exact=True).fill('AI合成连通-'+uuid.uuid4().hex[:8])
                page.get_by_role('button',name='创建',exact=True).click()
                page.get_by_role('button',name='＋新建工作区').click()
                page.get_by_label('工作区名称',exact=True).fill('合成资料单次验证')
                page.get_by_label('岗位方向',exact=True).fill('Java 后端')
                page.get_by_role('button',name='创建',exact=True).click()
                page.get_by_label('粘贴简历文本',exact=True).fill('合成测试候选人。订单演示项目：参与接口开发，部署仅为协助。不是真实经历。')
                page.get_by_role('button',name='预览并修正',exact=True).click()
                page.get_by_role('button',name='建立工作区简历',exact=True).click()
                expect(page.get_by_label('工作区简历正文',exact=True)).to_be_visible()
                expect(page.locator('aside')).to_contain_text('DeepSeek / deepseek-flash')
                expect(page.locator('aside')).to_contain_text('参考材料：0 份')
                question='仅针对提供的合成简历，用两句话指出职责应澄清什么。不要把协助部署改为独立负责，不要编造数据。'
                page.get_by_label('向 AI 提问',exact=True).fill(question)
                submitted = []
                page.on('request',lambda req: submitted.append(req.url) if req.method=='POST' and req.url.endswith('/conversation') else None)
                started = time.monotonic()
                # Exactly one click. Exceptions below do not trigger resend.
                result['real_calls_submitted'] = 1
                RESULT.write_text(json.dumps(result))
                with page.expect_response(lambda response: response.request.method=='POST' and response.url.endswith('/conversation')) as accepted:
                    page.get_by_role('button',name='发送',exact=True).click()
                response = accepted.value
                result['admission_http_status'] = response.status
                if response.status != 202:
                    result['error_code'] = response.json().get('code')
                    raise RuntimeError('REQUEST_NOT_ACCEPTED')
                task = response.json()
                request_id = task['id']
                base = response.url.rsplit('/conversation',1)[0]
                result['request_id'] = request_id
                result['workspace_path'] = base.split('/api/v1/',1)[1]
                RESULT.write_text(json.dumps(result))
                deadline = time.monotonic()+150
                while time.monotonic()<deadline:
                    state_response = context.request.get(base+'/requests/'+request_id)
                    if state_response.status!=200:
                        time.sleep(1)
                        continue
                    task = state_response.json()
                    if task['execution_status'] in ('SUCCEEDED','MODEL_FAILED','UNKNOWN') or task['record_status']=='SAVE_FAILED':
                        break
                    time.sleep(1)
                result.update(execution_status=task['execution_status'],record_status=task['record_status'],
                              error_code=task.get('error_code'), elapsed_seconds=round(time.monotonic()-started,2),
                              browser_submit_count=len(submitted), answer_characters=len(task.get('answer') or ''))
                if task['execution_status']!='SUCCEEDED' or task['record_status']!='SAVED':
                    raise RuntimeError('CALL_NOT_SUCCESSFUL_NO_AUTOMATIC_RETRY')
                expect(page.locator('aside').get_by_text('AI 回答',exact=True)).to_be_visible(timeout=10000)
                page.reload()
                expect(page.locator('aside').get_by_text('AI 回答',exact=True)).to_be_visible(timeout=10000)
                result['browser_reload_restored_answer'] = True
                # Read metadata only. Never print prompts, answers, keys, or HTTP headers.
                sql = "select json_build_object('requests',count(*),'supplier_id_present',bool_and(supplier_request_id is not null and supplier_request_id<>''),'usage',max(usage_json)) from m00_app.ai_request where id='"+str(uuid.UUID(request_id))+"';"
                db = subprocess.run(['docker','exec','-i','agentcv-m00-postgres','psql','-X','-t','-A','-U','agentcv_m00','-d','agentcv_m00'],
                                    input=sql, text=True, capture_output=True, check=True)
                result['metadata'] = json.loads(db.stdout.strip())
                if len(submitted)!=1 or result['metadata']['requests']!=1:
                    raise RuntimeError('UNEXPECTED_DUPLICATE_REQUEST')
                result['status'] = 'PASS'
                browser.close()
    except Exception as error:
        result['status'] = 'FAIL'
        # Exception messages from browser libraries may include page input: exclude them.
        result['failure_type'] = type(error).__name__
        if isinstance(error,RuntimeError):
            result['failure_code'] = str(error)
    finally:
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        RESULT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--start',action='store_true')
    args=parser.parse_args()
    if args.start:
        if RESULT.exists():
            previous=json.loads(RESULT.read_text())
            if previous.get('real_calls_submitted',1)!=0:
                raise SystemExit('A previous live submission exists; do not automatically repeat a paid call')
            RESULT.rename(RESULT.with_name('m04-live-preflight-failure.json'))
        LOG.parent.mkdir(exist_ok=True)
        with LOG.open('w') as output:
            process=subprocess.Popen([sys.executable,__file__],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
        print('Live synthetic test started; PID '+str(process.pid)+'. Read .local/m04-live-result.json for status; do not resend.')
    else:
        execute()