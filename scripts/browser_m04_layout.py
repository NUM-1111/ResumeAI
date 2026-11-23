"""AI overflow regression with browser-local synthetic responses; never calls a model."""
from playwright.sync_api import sync_playwright, expect


def verify(port,password):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,args=['--no-sandbox'])
        page=browser.new_page(viewport={'width':1440,'height':1000})
        root='http://127.0.0.1:'+str(port)
        page.goto(root)
        page.get_by_label('口令',exact=True).fill(password)
        page.get_by_role('button',name='登录',exact=True).click()
        page.get_by_role('button',name='＋新建档案').click()
        page.get_by_label('档案名称',exact=True).fill('滚动合成测试')
        page.get_by_role('button',name='创建',exact=True).click()
        page.get_by_role('button',name='＋新建工作区').click()
        page.get_by_label('工作区名称',exact=True).fill('滚动区')
        page.get_by_label('岗位方向',exact=True).fill('Java')
        # All conversation data are local to this browser, not persisted or externally sent.
        page.route('**/conversation',lambda route:route.fulfill(json={'requests':[
            {'id':str(i),'message':'合成问题','answer':'合成回答用于滚动验证。\n'*100,
             'execution_status':'SUCCEEDED','record_status':'SAVED','error_code':None,'model':'test'} for i in range(8)]}))
        page.get_by_role('button',name='创建',exact=True).click()
        expect(page.get_by_label('AI 消息记录',exact=True)).to_be_visible()
        expect(page.locator('.messages article')).to_have_count(8)
        page.get_by_label('向 AI 提问',exact=True).fill('保留的未发送合成草稿')
        page.get_by_role('button',name='参考资料',exact=True).click()
        dialog=page.get_by_role('dialog',name='参考资料',exact=True)
        expect(dialog).to_be_visible()
        expect(dialog.get_by_label('参考工作区简历',exact=True)).to_be_checked()
        dialog.get_by_label('参考工作区简历',exact=True).uncheck()
        expect(dialog.get_by_text('暂无个人材料，可从个人材料入口添加。',exact=True)).to_be_visible()
        page.keyboard.press('Escape')
        expect(dialog).not_to_be_visible()
        expect(page.get_by_role('button',name='参考资料',exact=True)).to_be_focused()
        expect(page.get_by_label('向 AI 提问',exact=True)).to_have_value('保留的未发送合成草稿')
        expect(page.locator('aside')).to_contain_text('不参考简历')
        for width in (1440,390):
            page.set_viewport_size({'width':width,'height':1000})
            if width==390:
                page.get_by_role('button',name='AI 对话',exact=True).click()
            result=page.locator('.messages').evaluate('''element => {
                element.scrollTop=150;
                return {overflow:element.scrollHeight>element.clientHeight, scrolled:element.scrollTop>0,
                    pageHeight:document.documentElement.scrollHeight, viewport:innerHeight};
            }''')
            assert result['overflow'] and result['scrolled'],result
            assert result['pageHeight']<result['viewport']+200,result
            expect(page.get_by_label('向 AI 提问',exact=True)).to_be_in_viewport()
            page.get_by_role('button',name='参考资料',exact=True).click()
            expect(dialog).to_be_visible()
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            dialog.get_by_role('button',name='完成',exact=True).click()
        browser.close()
    print('PASS: long AI history scrolls independently on desktop/mobile; input stays visible; no model calls')