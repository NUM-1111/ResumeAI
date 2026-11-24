"""Real background management HTTP/UI, synthetic seed in m00_test, no model calls."""
import json
import subprocess
import uuid
from playwright.sync_api import sync_playwright, expect


def verify(port,password):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,args=['--no-sandbox'])
        context=browser.new_context(viewport={'width':1440,'height':1000})
        page=context.new_page();page.set_default_timeout(7000)
        page.goto('http://127.0.0.1:'+str(port))
        page.get_by_label('口令',exact=True).fill(password)
        page.get_by_role('button',name='登录',exact=True).click()
        page.get_by_role('button',name='＋新建档案').click()
        page.get_by_label('档案名称',exact=True).fill('M07合成-'+uuid.uuid4().hex[:8])
        page.get_by_role('button',name='创建',exact=True).click()
        page.get_by_role('button',name='＋新建工作区').click()
        page.get_by_label('工作区名称',exact=True).fill('背景管理区')
        page.get_by_label('岗位方向',exact=True).fill('Java')
        page.get_by_role('button',name='创建',exact=True).click()
        expect(page.get_by_label('向 AI 提问',exact=True)).to_be_visible()
        position=page.evaluate("JSON.parse(localStorage.getItem('agentcv-m01-position'))")
        p=str(uuid.UUID(position['person']['id']));w=str(uuid.UUID(position['workspace']['id']))
        r,c,f,b=[str(uuid.uuid4()) for _ in range(4)]
        sql=f"""SET search_path=m00_test;
        INSERT INTO ai_request(id,profile_id,workspace_id,request_key,payload_hash,message,input_json,model,execution_status,record_status,answer)
        VALUES ('{r}','{p}','{w}','{uuid.uuid4()}','synthetic','合成种子','','test','SUCCEEDED','SAVED','合成背景');
        INSERT INTO background_candidate(id,profile_id,workspace_id,request_id,context_epoch,items_json,display_text,status)
        VALUES ('{c}','{p}','{w}','{r}',0,'["项目A负责部署"]','合成种子','CONFIRMED');
        INSERT INTO background_confirmation(id,profile_id,candidate_id,candidate_version,user_text,selected_json)
        VALUES ('{f}','{p}','{c}',1,'测试种子明确确认','[1]');
        INSERT INTO background(id,profile_id,confirmation_id,item_number,text) VALUES ('{b}','{p}','{f}',1,'项目A负责部署');"""
        subprocess.run(['docker','exec','-i','agentcv-m00-postgres','psql','-X','-v','ON_ERROR_STOP=1','-U','agentcv_m00','-d','agentcv_m00'],input=sql,text=True,check=True,stdout=subprocess.DEVNULL)
        page.get_by_label('向 AI 提问',exact=True).fill('保留这份草稿')
        expect(page.get_by_text('草稿已保存',exact=True)).to_be_visible()
        expect(page.locator('aside').get_by_role('button',name='查看个人背景',exact=True)).to_have_count(0)
        page.locator('nav').get_by_role('button',name='查看个人背景',exact=True).click()
        expect(page.locator('main').get_by_role('heading',name='已确认个人背景',exact=True)).to_be_visible()
        page.locator('main').get_by_role('button',name='刷新背景',exact=True).click()
        expect(page.locator('main').get_by_role('button',name='纠正',exact=True)).to_be_visible()
        expect(page.locator('main')).to_contain_text('这些操作不需要 AI')
        page.get_by_role('button',name='纠正',exact=True).click()
        editor=page.get_by_role('dialog',name='编辑个人背景',exact=True)
        expect(editor).to_be_visible()
        expect(editor.get_by_label('纠正后的背景',exact=True)).to_be_focused()
        editor.get_by_label('纠正后的背景',exact=True).fill('项目A仅协助部署')
        editor.get_by_role('button',name='提交纠正',exact=True).click()
        preview=page.get_by_role('dialog',name='确认背景变更')
        expect(preview).to_be_visible()
        expect(preview.get_by_role('button',name='取消背景变更',exact=True)).to_be_focused()
        expect(page.get_by_text('原记录：项目A负责部署',exact=True)).to_be_visible()
        expect(page.get_by_text('拟修改为：项目A仅协助部署',exact=True)).to_be_visible()
        # Observe a completed real polling cycle with this list-origin proposal.
        with page.expect_response(lambda response:'/background-changes' in response.url and response.request.method=='GET') as polled:
            page.wait_for_timeout(1700)
        assert polled.value.json().get('source_request_id') is None
        expect(page.locator('aside').get_by_role('button',name='核对并确认背景变更',exact=True)).to_have_count(0)
        # A failed save stays inside the modal, keeping exact content available for retry.
        page.route('**/background-changes/*/confirm',lambda route:route.fulfill(status=503,json={'code':'PERSISTENCE_FAILED','message':'合成保存故障，请重试','retryable':True}))
        preview.get_by_role('button',name='确认纠正',exact=True).click()
        expect(preview).to_be_visible()
        expect(preview.get_by_role('alert')).to_contain_text('合成保存故障')
        page.unroute('**/background-changes/*/confirm')
        preview.get_by_role('button',name='确认纠正',exact=True).click()
        expect(preview).to_have_count(0)
        expect(page.locator('main li')).to_contain_text('项目A仅协助部署')
        expect(page.get_by_label('向 AI 提问',exact=True)).to_have_value('保留这份草稿')
        page.get_by_role('button',name='撤回',exact=True).click()
        expect(preview).to_be_visible()
        page.keyboard.press('Escape')
        expect(preview).to_have_count(0)
        expect(page.locator('main li')).to_have_count(1)
        page.get_by_role('button',name='撤回',exact=True).click()
        preview.get_by_role('button',name='确认撤回',exact=True).click()
        expect(page.locator('main li')).to_have_count(0)
        page.get_by_role('button',name='刷新背景',exact=True).click()
        expect(page.locator('main')).to_contain_text('尚无已确认背景')
        expect(page.get_by_label('向 AI 提问',exact=True)).to_have_value('保留这份草稿')
        # A dialogue-origin proposal remains available in the AI area.
        dialogue={'id':str(uuid.uuid4()),'workspace_id':w,'background_id':b,'expected_version':1,
                  'old_text':'对话合成原记录','new_text':'对话合成新记录','kind':'CORRECT','source_request_id':r}
        page.route('**/background-changes',lambda route:route.fulfill(json=dialogue))
        chat_entry=page.locator('aside').get_by_role('button',name='核对并确认背景变更',exact=True)
        expect(chat_entry).to_be_visible()
        chat_entry.click()
        expect(preview).to_be_visible()
        expect(preview).to_contain_text('对话合成新记录')
        page.keyboard.press('Escape')
        expect(preview).to_have_count(0)
        expect(chat_entry).to_be_visible()
        browser.close()
    print('PASS: M07 real HTTP correction, before/after confirmation, cancel withdrawal, confirmed withdrawal and draft preserved; synthetic seed; no model calls')