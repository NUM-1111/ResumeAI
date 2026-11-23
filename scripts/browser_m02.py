"""Upload, original download and independent copy through the real M02 UI."""
import uuid
import io
import zipfile
from pathlib import Path
from playwright.sync_api import sync_playwright, expect


def verify(port, password):
    address = 'http://127.0.0.1:' + str(port)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, args=['--no-sandbox'])
        context = browser.new_context(viewport={'width':1440,'height':1000}, accept_downloads=True)
        font = Path('/mnt/c/Windows/Fonts/msyh.ttc')
        if font.exists():
            context.route('**/__test_font', lambda route: route.fulfill(body=font.read_bytes(),content_type='font/collection'))
            context.add_init_script("document.addEventListener('DOMContentLoaded',()=>{const s=document.createElement('style');s.textContent=\"@font-face{font-family:T;src:url('/__test_font')}body,input,textarea,button{font-family:T,system-ui!important}\";document.head.append(s);});")
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(address)
        page.get_by_label('口令',exact=True).fill(password)
        page.get_by_role('button',name='登录',exact=True).click()
        page.get_by_role('button',name='＋新建档案').click()
        page.get_by_label('档案名称',exact=True).fill('文件体验-'+uuid.uuid4().hex[:8])
        page.get_by_role('button',name='创建',exact=True).click()
        def new_workspace(name):
            page.get_by_role('button',name='＋新建工作区').click()
            page.get_by_label('工作区名称',exact=True).fill(name)
            page.get_by_label('岗位方向',exact=True).fill('Java')
            page.get_by_role('button',name='创建',exact=True).click()
            expect(page.get_by_label('粘贴简历文本',exact=True)).to_be_visible()
        new_workspace('上传来源区')
        file_input = page.get_by_label('上传 TXT / DOCX / PDF',exact=True)
        file_input.set_input_files({'name':'不支持.doc','mimeType':'application/msword','buffer':b'bad'})
        expect(page.get_by_role('alert').filter(has_text='仅支持')).to_be_visible()
        file_input.set_input_files({'name':'损坏.pdf','mimeType':'application/pdf','buffer':b'bad'})
        expect(page.locator('#import-error')).to_contain_text('文件损坏',timeout=10000)
        # Minimal valid OOXML document, generated without additional packages.
        output = io.BytesIO()
        with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as doc:
            doc.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
            doc.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
            doc.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>合成 DOCX 原文：协助部署。</w:t></w:r></w:p></w:body></w:document>')
        original = output.getvalue()
        file_input.set_input_files({'name':'中文简历.docx','mimeType':'application/vnd.openxmlformats-officedocument.wordprocessingml.document','buffer':original})
        expect(page.get_by_label('粘贴简历文本',exact=True)).to_have_value('合成 DOCX 原文：协助部署。\n')
        page.get_by_label('粘贴简历文本',exact=True).fill('修正后的独立工作区简历')
        page.get_by_role('button',name='建立工作区简历',exact=True).click()
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('修正后的独立工作区简历')
        page.get_by_text('来源信息',exact=True).click()
        with page.expect_download() as download_info:
            page.get_by_role('link',name='打开/下载导入文件').click()
        assert Path(download_info.value.path()).read_bytes() == original
        page.get_by_role('button',name='← 返回上层',exact=True).click()
        new_workspace('复制目标区')
        page.get_by_role('button',name='复制其他工作区简历',exact=True).click()
        page.get_by_role('button',name='上传来源区 · Java',exact=True).click()
        expect(page.get_by_label('来源简历预览',exact=True)).to_have_value('修正后的独立工作区简历')
        assert page.get_by_label('来源简历预览',exact=True).get_attribute('readonly') is not None
        page.get_by_role('button',name='确认复制并开始编辑',exact=True).click()
        expect(page.locator('header')).to_contain_text('复制目标区')
        expect(page.get_by_role('button',name='确认复制并开始编辑',exact=True)).to_have_count(0)
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('修正后的独立工作区简历')
        page.get_by_label('工作区简历正文',exact=True).fill('副本独立编辑')
        with page.expect_response(lambda response: response.request.method=='PUT' and response.url.endswith('/resume')) as saved:
            page.get_by_role('button',name='保存 / 重试',exact=True).click()
        assert saved.value.status == 200
        expect(page.get_by_role('status').filter(has_text='已保存')).to_be_visible()
        with page.expect_response(lambda response: response.request.method=='GET' and '/workspaces?' in response.url):
            page.get_by_role('button',name='← 返回上层',exact=True).click()
        with page.expect_response(lambda response: response.request.method=='GET' and response.url.endswith('/resume')) as reopened:
            page.get_by_role('button',name='上传来源区',exact=True).click()
        assert reopened.value.status == 200
        expect(page.get_by_label('工作区简历正文',exact=True)).to_have_value('修正后的独立工作区简历')
        page.evaluate('document.fonts.ready')
        page.screenshot(path='/root/java/ResumeAI/.local/m02-desktop.png',full_page=True)
        assert not errors, errors
        browser.close()
    print('PASS: M02 DOCX upload/repair/original download/independent copy and upload errors')