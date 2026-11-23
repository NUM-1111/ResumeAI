"""M01 browser contract on synthetic data and a temporary operator password."""
import json
import uuid
import urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright, expect


def verify(port, password):
    address = 'http://127.0.0.1:' + str(port)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, args=['--no-sandbox'])
        context = browser.new_context(viewport={'width':1440,'height':1000})
        # Existing host font is test-only and never redistributed.
        font = Path('/mnt/c/Windows/Fonts/msyh.ttc')
        if font.is_file():
            context.route('**/__test_font', lambda route: route.fulfill(body=font.read_bytes(),content_type='font/collection'))
            context.add_init_script("document.addEventListener('DOMContentLoaded',()=>{const style=document.createElement('style');style.textContent=\"@font-face{font-family:TestChinese;src:url('/__test_font')}body,input,textarea,button{font-family:TestChinese,system-ui!important}\";document.head.append(style);});")
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(address)
        page.get_by_label('口令', exact=True).fill('wrong-password')
        page.get_by_role('button', name='登录', exact=True).click()
        expect(page.get_by_role('alert')).to_contain_text('账号或口令不正确')
        page.get_by_label('口令', exact=True).fill(password)
        page.get_by_role('button', name='登录', exact=True).click()
        expect(page.get_by_role('button',name='＋新建档案')).to_be_visible()
        page.get_by_role('button',name='＋新建档案').click()
        expect(page.get_by_label('档案名称',exact=True)).to_be_focused()
        page.keyboard.press('Shift+Tab')
        expect(page.get_by_role('button',name='创建',exact=True)).to_be_focused()
        page.keyboard.press('Escape')
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.get_by_role('button',name='＋新建档案')).to_be_focused()
        page.get_by_role('button',name='＋新建档案').click()
        page.get_by_role('button',name='创建',exact=True).click()
        expect(page.get_by_role('alert')).to_contain_text('请填写名称')
        name = 'M01-' + uuid.uuid4().hex[:10]
        page.get_by_label('档案名称',exact=True).fill(name)
        page.get_by_role('button',name='创建',exact=True).click()
        page.get_by_role('button',name='＋新建工作区').click()
        page.get_by_label('工作区名称',exact=True).fill('Java工作区')
        page.get_by_label('岗位方向',exact=True).fill('Java后端')
        page.get_by_role('button',name='创建',exact=True).click()
        draft = page.get_by_label('粘贴简历文本',exact=True)
        draft.fill('合成候选人\n职责：协助部署，仅用于测试。')
        page.get_by_role('button',name='退出登录',exact=True).click()
        expect(page.get_by_role('dialog')).to_contain_text('放弃后需要重新导入')
        page.get_by_role('button',name='留在当前页面').click()
        expect(draft).to_have_value('合成候选人\n职责：协助部署，仅用于测试。')
        page.get_by_role('button',name='预览并修正',exact=True).click()
        page.get_by_role('button',name='建立工作区简历',exact=True).click()
        editor = page.get_by_label('工作区简历正文',exact=True)
        expect(editor).to_have_value('合成候选人\n职责：协助部署，仅用于测试。')
        editor.fill('已保存的合成简历')
        expect(page.get_by_role('status').filter(has_text='已保存')).to_be_visible()
        page.reload()
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('已保存的合成简历')
        # Another tab changes the same version; the original must preserve its input.
        other = context.new_page()
        other.goto(address)
        expect(other.get_by_label('工作区简历正文',exact=True)).to_have_value('已保存的合成简历')
        other.get_by_label('工作区简历正文',exact=True).fill('另页已保存')
        expect(other.get_by_role('status').filter(has_text='已保存')).to_be_visible()
        page.get_by_label('工作区简历正文',exact=True).fill('冲突页未保存')
        expect(page.get_by_role('status').filter(has_text='其他页面更新')).to_be_visible()
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('冲突页未保存')
        other.close()
        page.get_by_role('button',name='查看最新内容供核对').click()
        expect(page.get_by_label('供手动复制或核对的文本')).to_have_value('另页已保存')
        page.get_by_role('button',name='放弃本页编辑并载入最新内容').click()
        page.get_by_role('button',name='放弃未保存内容并继续').click()
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('另页已保存')
        # Fail a save at the browser transport, without stopping the database.
        page.route('**/resume', lambda route: route.abort() if route.request.method == 'PUT' else route.continue_())
        page.get_by_label('工作区简历正文',exact=True).fill('故障时保留输入')
        expect(page.get_by_role('status').filter(has_text='网络或服务暂不可用')).to_be_visible()
        page.get_by_role('button',name='退出登录',exact=True).click()
        expect(page.get_by_role('dialog')).to_be_visible()
        page.get_by_role('button',name='留在当前页面').click()
        page.unroute('**/resume')
        page.get_by_role('button',name='保存 / 重试',exact=True).click()
        expect(page.get_by_role('status').filter(has_text='已保存')).to_be_visible()
        # Expire the session behind the page, then test original-page reauthentication.
        csrf = context.request.get(address+'/api/v1/csrf').json()
        assert context.request.post(address+'/logout',headers={csrf['header_name']:csrf['token']}).status == 204
        page.get_by_label('工作区简历正文',exact=True).fill('登录过期仍保留')
        expect(page.get_by_role('dialog',name='重新登录')).to_be_visible()
        page.get_by_label('口令',exact=True).fill(password)
        page.get_by_role('button',name='登录',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('登录过期仍保留')
        # No automatic replay after successful login.
        page.once('dialog', lambda dialog: dialog.accept())
        page.reload()
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('故障时保留输入')
        # Delay only the first PUT response, then edit while it is in flight.
        page.evaluate('''() => {
          const original = window.fetch.bind(window); let delayed = false;
          window.fetch = async (...args) => {
            const response = await original(...args);
            if (!delayed && args[1]?.method === 'PUT') {
              delayed = true; await new Promise(resolve => setTimeout(resolve, 1600));
            }
            return response;
          };
        }''')
        editor = page.get_by_label('工作区简历正文',exact=True)
        editor.fill('慢响应第一稿')
        expect(page.get_by_role('status').filter(has_text='正在保存')).to_be_visible()
        editor.fill('慢响应期间的新稿')
        expect(page.get_by_role('status').filter(has_text='已保存')).to_be_visible(timeout=12000)
        page.reload()
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('慢响应期间的新稿')
        for width in (900,390):
            page.set_viewport_size({'width':width,'height':900})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        assert not errors, errors
        page.evaluate('document.fonts.ready')
        page.screenshot(path='/root/java/ResumeAI/.local/m01-mobile.png',full_page=True)
        page.set_viewport_size({'width':1440,'height':1000})
        page.screenshot(path='/root/java/ResumeAI/.local/m01-desktop.png',full_page=True)
        browser.close()
    print('PASS: M01 browser login/create/initialize/save/reopen/conflict/failure/session/narrow-screen')