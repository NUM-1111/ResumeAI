"""M06 browser contract checks; response fixtures are explicit, no model calls."""
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
        page.get_by_label('档案名称',exact=True).fill('背景合成-'+uuid.uuid4().hex[:8])
        page.get_by_role('button',name='创建',exact=True).click()
        page.get_by_role('button',name='＋新建工作区').click()
        page.get_by_label('工作区名称',exact=True).fill('背景测试区')
        page.get_by_label('岗位方向',exact=True).fill('Java')
        page.get_by_role('button',name='创建',exact=True).click()
        draft=page.get_by_label('向 AI 提问',exact=True)
        expect(draft).to_be_visible()
        display='待保存到当前人物的个人背景：\n1. 项目A中协助部署，并非独立负责。\n2. 开发订单接口。\n确认后供该人物各工作区使用。'
        candidate={'id':str(uuid.uuid4()),'version':1,'request_id':str(uuid.uuid4()),'display_text':display,'items':['项目A中协助部署，并非独立负责。','开发订单接口。']}
        saved=[];attempts=[];model_sends=[]
        page.route('**/model',lambda route:route.fulfill(json={'configured':True}))
        page.route('**/ai-scope',lambda route:route.fulfill(json={'resume_version':0,'resume_available':False,'scope_version':0,'materials':[],'background_count':len(saved)}))
        page.route('**/background-candidates',lambda route:route.fulfill(json=candidate if not saved else {}))
        page.route('**/conversation?*',lambda route:route.fulfill(json={'requests':[{'id':candidate['request_id'],'message':'整理合成经历','answer':display,'execution_status':'SUCCEEDED','record_status':'SAVED','error_code':None,'model':'fixture'}],'state':{'version':0,'epoch':0,'boundary_at':'-infinity'}}))
        def confirm(route):
            attempts.append(route.request.headers.get('idempotency-key'))
            payload=route.request.post_data_json
            assert payload['displayed_text']==display
            assert payload['user_text']=='确认保存全部'
            if len(attempts)==1:
                route.fulfill(status=503,json={'code':'PERSISTENCE_FAILED','message':'合成保存故障','retryable':True})
            else:
                saved.extend({'id':str(uuid.uuid4()),'text':text} for text in candidate['items'])
                route.fulfill(json={'saved':True,'items':saved})
        page.route('**/background-candidates/*/confirm',confirm)
        page.route('**/backgrounds',lambda route:route.fulfill(json={'items':saved}))
        page.on('request',lambda req:model_sends.append(req.url) if req.method=='POST' and req.url.endswith('/conversation') else None)
        page.reload()
        expect(page.locator('aside')).to_contain_text('尚未保存')
        draft.fill('确认保存全部')
        page.get_by_role('button',name='发送',exact=True).click()
        expect(page.get_by_role('button',name='重试保存背景',exact=True)).to_be_visible()
        expect(draft).to_have_value('确认保存全部')
        page.get_by_role('button',name='重试保存背景',exact=True).click()
        expect(page.get_by_text('已保存到个人背景：'+'；'.join(candidate['items']),exact=True)).to_be_visible()
        assert len(attempts)==2 and attempts[0]==attempts[1]
        assert not model_sends
        draft.fill('下一条未发送草稿')
        page.get_by_role('button',name='查看个人背景',exact=True).click()
        expect(page.locator('main')).to_contain_text(candidate['items'][0])
        expect(page.locator('main')).to_contain_text('开发订单接口。')
        expect(draft).to_have_value('下一条未发送草稿')
        page.get_by_role('button',name='返回工作区简历',exact=True).click()
        expect(draft).to_have_value('下一条未发送草稿')
        task={'id':str(uuid.uuid4()),'message':'慢响应测试','answer':None,'execution_status':'CALLING','record_status':'PENDING','error_code':None,'model':'fixture'}
        page.route('**/conversation?*',lambda route:route.fulfill(json={'requests':[task],'state':{'version':0,'epoch':0,'boundary_at':'-infinity'}}))
        page.route('**/requests/'+task['id'],lambda route:route.fulfill(json=task))
        def stop(route):
            task.update(execution_status='UNKNOWN',record_status='SAVED',error_code='UNKNOWN_ACKNOWLEDGED')
            route.fulfill(json=task)
        page.route('**/stop-waiting',stop)
        expect(page.get_by_role('button',name='停止等待',exact=True)).to_be_visible()
        page.once('dialog',lambda dialog:dialog.accept())
        page.get_by_role('button',name='停止等待',exact=True).click()
        expect(page.get_by_role('button',name='停止等待',exact=True)).to_have_count(0)
        review=page.get_by_role('button',name='已核对最新对话',exact=True)
        if review.count():review.click()
        expect(page.get_by_role('button',name='发送',exact=True)).to_be_enabled()
        browser.close()
    print('PASS: M06 browser fixtures: partial confirmation, failure/retry same key, real list view preserves draft; no model calls')