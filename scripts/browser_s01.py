"""Use preinstalled Playwright 1.48/Chromium only, synthetic data, real JAR."""
import subprocess
import time
import uuid
from playwright.sync_api import sync_playwright, expect
from local import ROOT, LOCAL, environment, config, listening
from passwords import test_credentials

if listening(8080):
    raise SystemExit('8080 occupied; no process stopped')
with (LOCAL / 'browser-server.log').open('a') as log:
    env, password = test_credentials(environment())
    server = subprocess.Popen(['java','-jar',str(ROOT/'target/agentcv-0.0.1-SNAPSHOT.jar')], cwd=str(ROOT),env=env,stdout=log,stderr=log)
    try:
        for _ in range(30):
            if listening(8080): break
            if server.poll() is not None: raise RuntimeError('JAR exited')
            time.sleep(1)
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True,args=['--no-sandbox'])
            context=browser.new_context()
            page=context.new_page()
            failures=[]
            page.on('pageerror',lambda e:failures.append(str(e)))
            page.goto('http://127.0.0.1:8080/')
            page.locator('input[name=username]').fill('operator')
            page.locator('input[name=password]').fill(password)
            page.get_by_role('button',name='Sign in',exact=True).click()
            expect(page.get_by_role('heading',name='AgentCV · 文本简历工作台')).to_be_visible()
            name='页面合成人物-'+str(uuid.uuid4())[:8]
            page.get_by_label('新档案姓名',exact=True).fill(name)
            page.get_by_role('button',name='创建档案',exact=True).click()
            expect(page.get_by_role('heading',name=name+' · 工作区')).to_be_visible()
            page.get_by_label('工作区名称',exact=True).fill('后端-页面测试')
            page.get_by_label('岗位方向',exact=True).fill('Java后端')
            page.get_by_role('button',name='创建独立工作区',exact=True).click()
            expect(page.get_by_role('heading',name=name+' / 后端-页面测试')).to_be_visible()
            page.get_by_label('材料检查文本',exact=True).fill('合成经历：参与接口设计')
            page.get_by_role('button',name='保存为待检查材料',exact=True).click()
            expect(page.get_by_role('button',name='确认检查后的文本',exact=True)).to_be_visible()
            expect(page.get_by_label('当前简历',exact=True)).to_have_count(0)
            page.get_by_label('材料检查文本',exact=True).fill('合成经历：负责接口设计，协助部署')
            page.get_by_role('button',name='确认检查后的文本',exact=True).click()
            expect(page.get_by_text('文本已检查确认可用，不代表确认经历真实',exact=True)).to_be_visible()
            page.get_by_role('button',name='以所选材料建立当前稿',exact=True).click()
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('合成经历：负责接口设计，协助部署')
            page.get_by_label('当前简历',exact=True).fill('保存重开后的合成中文简历')
            expect(page.get_by_text('已保存 · 服务端修订 v2',exact=True)).to_be_visible(timeout=10000)
            page.get_by_role('button',name='重新打开服务端当前稿',exact=True).click()
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('保存重开后的合成中文简历')
            print('PASS: browser login/create/import/check/initialize/autosave/reopen',flush=True)
            # Force storage/transport failure without adding production fault endpoints.
            context.route('**/api/v1/profiles/**/resume',lambda route:route.abort() if route.request.method=='PUT' else route.continue_())
            page.get_by_label('当前简历',exact=True).fill('断网失败时仍保留的页面稿')
            expect(page.get_by_text('保存失败 · 未保存 · 服务端修订 v2',exact=True)).to_be_visible(timeout=10000)
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('断网失败时仍保留的页面稿')
            page.on('dialog',lambda dialog:dialog.dismiss())
            page.get_by_role('button',name='重新打开服务端当前稿',exact=True).click()
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('断网失败时仍保留的页面稿')
            context.unroute('**/api/v1/profiles/**/resume')
            page.get_by_role('button',name='立即保存 / 重试',exact=True).click()
            expect(page.get_by_text('已保存 · 服务端修订 v3',exact=True)).to_be_visible(timeout=10000)
            print('PASS: browser failure retains draft/blocks silent leave/retry saves',flush=True)
            # Fresh page proves re-open uses server persistence, not just editor memory.
            page.reload()
            page.get_by_role('button',name=name,exact=True).click()
            page.get_by_role('button',name='后端-页面测试 / Java后端',exact=True).click()
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('断网失败时仍保留的页面稿')
            page.get_by_label('工作区名称',exact=True).fill('独立副本')
            page.get_by_label('岗位方向',exact=True).fill('Java后端')
            page.get_by_role('button',name='创建独立工作区',exact=True).click()
            expect(page.get_by_role('heading',name=name+' / 独立副本')).to_be_visible()
            page.get_by_label('复制来源工作区',exact=True).select_option(label='后端-页面测试')
            page.get_by_role('button',name='复制为独立当前稿',exact=True).click()
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('断网失败时仍保留的页面稿')
            # Second browser tab reads the same revision, first tab wins a save.
            other=context.new_page()
            other.goto('http://127.0.0.1:8080/')
            other.get_by_role('button',name=name,exact=True).click()
            other.get_by_role('button',name='独立副本 / Java后端',exact=True).click()
            expect(other.get_by_label('当前简历',exact=True)).to_have_value('断网失败时仍保留的页面稿')
            page.get_by_label('当前简历',exact=True).fill('副本独立保存内容')
            expect(page.get_by_text('已保存 · 服务端修订 v2',exact=True)).to_be_visible(timeout=10000)
            other.get_by_label('当前简历',exact=True).fill('过期标签页本地文本')
            expect(other.get_by_text('版本冲突 · 未保存 · 服务端修订 v1',exact=True)).to_be_visible(timeout=10000)
            expect(other.get_by_label('当前简历',exact=True)).to_have_value('过期标签页本地文本')
            print('PASS: browser refresh/copy independence/multi-tab version conflict',flush=True)
            other.close(run_before_unload=False)
            page.get_by_role('button',name='后端-页面测试 / Java后端',exact=True).click()
            expect(page.get_by_label('当前简历',exact=True)).to_have_value('断网失败时仍保留的页面稿')
            # Browser-side UTF-8 upload and corrected extraction path.
            page.get_by_role('button',name='新建文本导入',exact=True).click()
            page.get_by_label('上传UTF-8文本',exact=True).set_input_files({
                'name':'合成上传.txt','mimeType':'text/plain','buffer':'上传中文材料'.encode('utf-8')})
            page.get_by_role('button',name='保存为待检查材料',exact=True).click()
            expect(page.get_by_role('button',name='确认检查后的文本',exact=True)).to_be_visible()
            page.get_by_label('材料检查文本',exact=True).fill('修正后的上传中文材料')
            page.get_by_role('button',name='确认检查后的文本',exact=True).click()
            expect(page.get_by_text('文本已检查确认可用，不代表确认经历真实',exact=True)).to_be_visible()
            print('PASS: browser UTF-8 txt upload/check correction/confirm',flush=True)
            page.screenshot(path=str(LOCAL/'s01-workbench.png'),full_page=True)
            assert not failures,failures
            browser.close()
    finally:
        server.terminate()
        try: server.wait(timeout=15)
        except subprocess.TimeoutExpired: server.kill();server.wait()