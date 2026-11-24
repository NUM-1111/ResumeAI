import { h, ref, onMounted, nextTick } from 'vue';
import { request, ApiError } from './api';
import { materials } from './materials';
import { conversation } from './conversation';
import { backgrounds } from './backgrounds';
type Person = { id: string; display_name: string; created_at: string };
type Workspace = { id: string; profile_id: string; name: string; direction: string; initialized: boolean; created_at: string; last_edited_at: string | null };
type Resume = { initialized: boolean; text?: string; version: number; source_kind?:string; origin?:{original_name?:string;file_status?:string;source_workspace_name?:string}; file_warning?:string };
type Page<T> = { items: T[]; next_cursor: string | null };
const button = (label: string, action: () => unknown, disabled = false) => h('button', { type:'button',onClick:action,disabled },label);
export const workbench = { setup() {
  const authenticated=ref(false), expired=ref(false), ready=ref(false), busy=ref(false);
  const username=ref('operator'), password=ref(''), loginError=ref(''), notice=ref('');
  const people=ref<Person[]>([]), workspaces=ref<Workspace[]>([]), person=ref<Person|null>(null), workspace=ref<Workspace|null>(null);
  const nav=ref<'people'|'workspaces'|'contents'>('people'), search=ref(''), cursor=ref<string|null>(null), navOpen=ref(false), view=ref('document');
  const text=ref(''), saved=ref(''), version=ref(0), initialized=ref(false), preview=ref(false), saving=ref(false), blocked=ref(false), state=ref(''), fallback=ref('');
  const modal=ref<null|'person'|'workspace'|'leave'>(null), name=ref(''), direction=ref(''), error=ref('');
  const materialMode=ref(false),materialDirty=ref(false),materialEpoch=ref(0);
  const materialBusy=ref(false);
  const referenceCount=ref(0);
  const aiScopeRevision=ref(0);
  const backgroundMode=ref(false);
  let conversationGuard:(()=>Promise<boolean>)|null=null;
  async function refreshReferenceCount(){if(person.value&&workspace.value){const value=await request<{items:unknown[]}>(base()+'/references');referenceCount.value=value.items.length;}else referenceCount.value=0;}
  const upload=ref<{upload_id:string;original_name:string;limitation:string}|null>(null), origin=ref<Resume|null>(null), importing=ref(false), importError=ref('');
  const copyMode=ref(false), sources=ref<Workspace[]>([]), sourceQuery=ref(''), sourceCursor=ref<string|null>(null);
  const selectedSource=ref<Workspace|null>(null), sourceResume=ref<Resume|null>(null);
  let importGeneration=0;
  let timer:ReturnType<typeof setTimeout>|undefined, flight:Promise<boolean>|null=null;
  let pending:(()=>Promise<void>)|null=null, trigger:HTMLElement|null=null;
  let op:{signature:string;key:string}|null=null, creation:{signature:string;key:string}|null=null;
  const dirty=()=>initialized.value?text.value!==saved.value:!!text.value||!!upload.value||!!sourceResume.value;
  const base=()=>`/api/v1/profiles/${person.value!.id}/workspaces/${workspace.value!.id}`;
  function operation(signature:string,create=false) {
    let value=create?creation:op;
    if(!value||value.signature!==signature)value={signature,key:crypto.randomUUID()};
    if(create)creation=value;else op=value;
    return value.key;
  }
  function fail(e:unknown) {
    if(authenticated.value&&e instanceof ApiError&&(e.status===401||e.status===403)){expired.value=true;password.value='';nextTick(()=>document.querySelector<HTMLInputElement>('#password')?.focus());}
    return e instanceof ApiError?e.message:'网络或服务暂不可用，请保留输入后重试。';
  }
  async function safe(action:()=>Promise<void>){try{await action();}catch(e){notice.value=fail(e);}}
  async function list(append=false){
    const path=nav.value==='people'?'/api/v1/profiles':`/api/v1/profiles/${person.value!.id}/workspaces`;
    const params=new URLSearchParams({q:search.value});if(append&&cursor.value)params.set('after',cursor.value);
    const page=await request<Page<Person|Workspace>>(`${path}?${params}`);
    if(nav.value==='people')people.value=(append?[...people.value,...page.items]:page.items) as Person[];
    else workspaces.value=(append?[...workspaces.value,...page.items]:page.items) as Workspace[];
    cursor.value=page.next_cursor;
  }
  async function copy(value=text.value){try{await navigator.clipboard.writeText(value);notice.value='已复制。';}catch{fallback.value=value;notice.value='自动复制不可用，请从下方文本框手动复制。';}}
  async function save():Promise<boolean>{
    if(flight){const ok=await flight;return ok&&dirty()&&!blocked.value?save():!dirty();}
    if(!initialized.value||!dirty())return true;if(blocked.value||expired.value)return false;
    const value=text.value,path=base()+'/resume',body={text:value,expected_version:version.value};
    saving.value=true;state.value='正在保存…';
    flight=(async()=>{try{
      const result=await request<Resume>(path,'PUT',body,operation(path+JSON.stringify(body)));
      version.value=result.version;saved.value=value;op=null;state.value=dirty()?'有未保存修改':'已保存';return true;
    }catch(e){blocked.value=e instanceof ApiError&&e.code==='VERSION_CONFLICT';state.value=fail(e);return false;}finally{saving.value=false;}})();
    const result=await flight;flight=null;return result;
  }
  function edit(value:string){text.value=value;clearTimeout(timer);if(initialized.value){state.value='有未保存修改';if(!blocked.value&&!expired.value)timer=setTimeout(async()=>{if(await save()&&dirty())timer=setTimeout(()=>void save(),400);},800);}}
  function open(kind:'person'|'workspace'|'leave'){
    trigger=document.activeElement as HTMLElement;modal.value=kind;error.value='';
    if(kind!=='leave'){name.value='';direction.value='';creation=null;}
    nextTick(()=>document.querySelector<HTMLElement>('[role=dialog] input,[role=dialog] button')?.focus());
  }
  function close(){modal.value=null;pending=null;nextTick(()=>trigger?.focus());}
  async function leave(action:()=>Promise<void>){
    if(conversationGuard&&!await conversationGuard())return;
    if(materialBusy.value){notice.value='个人材料正在保存，请等待完成后切换。';return;}
    if(materialDirty.value){if(!window.confirm('个人材料有未保存输入，明确放弃后切换？'))return;materialDirty.value=false;materialEpoch.value++;}
    clearTimeout(timer);if(flight)await flight;if(initialized.value&&dirty())await save();
    if(importing.value){notice.value='正在解析文件，请等待结束后切换。';return;}
    if(dirty()){pending=action;open('leave');return;}await action();
  }
  async function selectWorkspace(item:Workspace){await leave(async()=>{
    const result=await request<Resume>(`/api/v1/profiles/${item.profile_id}/workspaces/${item.id}/resume`);
    workspace.value=item;initialized.value=result.initialized;version.value=result.version;text.value=result.text??'';saved.value=text.value;
    blocked.value=false;preview.value=false;op=null;state.value=result.initialized?'已保存':'';nav.value='contents';navOpen.value=false;
    origin.value=result;upload.value=null;copyMode.value=false;sourceResume.value=null;selectedSource.value=null;importError.value='';importGeneration++;
    materialMode.value=false;
    backgroundMode.value=false;
    await refreshReferenceCount();
    localStorage.setItem('agentcv-m01-position',JSON.stringify({person:person.value,workspace:item}));
  });}
  async function selectPerson(item:Person){await leave(async()=>{person.value=item;workspace.value=null;text.value='';saved.value='';initialized.value=false;materialMode.value=false;backgroundMode.value=false;conversationGuard=null;nav.value='workspaces';search.value='';await list();});}
  async function create(){
    if(busy.value)return;
    if(!name.value.trim()){error.value='请填写名称。';return;}
    if(modal.value==='workspace'&&!direction.value.trim()){error.value='请填写岗位方向。';return;}
    busy.value=true;try{
      const p=modal.value==='person',path=p?'/api/v1/profiles':`/api/v1/profiles/${person.value!.id}/workspaces`;
      const body=p?{display_name:name.value}:{name:name.value,direction:direction.value};
      const item=await request<Person|Workspace>(path,'POST',body,operation(path+JSON.stringify(body),true));close();
      if(p)await selectPerson(item as Person);else await selectWorkspace(item as Workspace);
    }catch(e){error.value=fail(e);}finally{busy.value=false;}
  }
  async function initialize(){if(busy.value)return;busy.value=true;try{
    const path=base()+'/resume/initialize',value=text.value,body={text:value,expected_version:0,upload_id:upload.value?.upload_id??null};
    const result=await request<Resume>(path,'POST',body,operation(path+JSON.stringify(body)));
    initialized.value=true;saved.value=value;version.value=result.version;preview.value=false;op=null;state.value=result.file_warning??'已保存';origin.value=result;upload.value=null;
  }catch(e){state.value=fail(e);}finally{busy.value=false;}}
  async function uploadFile(file:File|undefined){
    if(!file||busy.value||importing.value)return;
    importError.value='';
    if(!/\.(txt|docx|pdf)$/i.test(file.name)){importError.value='仅支持 TXT、DOCX 和文本层 PDF。';return;}
    if(file.size===0||file.size>262144){importError.value='文件为空或超过 256 KiB，请缩小文件或粘贴文字。';return;}
    const perform=async()=>{
      const generation=++importGeneration,path=base()+'/resume/upload-preview';importing.value=true;
      try{
        const bytes=new Uint8Array(await file.arrayBuffer());let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);
        const body={name:file.name,base64:btoa(binary)};
        const result=await request<{upload_id:string;original_name:string;limitation:string;text:string}>(path,'POST',body,operation(path+JSON.stringify(body)));
        if(generation!==importGeneration)return;
        text.value=result.text;upload.value=result;preview.value=true;copyMode.value=false;sourceResume.value=null;state.value='';
      }catch(e){if(generation===importGeneration){importError.value=fail(e);nextTick(()=>document.querySelector<HTMLElement>('#import-error')?.focus());}}
      finally{if(generation===importGeneration)importing.value=false;}
    };
    await leave(perform);
  }
  async function listSources(append=false){
    const params=new URLSearchParams({q:sourceQuery.value});if(append&&sourceCursor.value)params.set('after',sourceCursor.value);
    const result=await request<Page<Workspace>>(`/api/v1/profiles/${person.value!.id}/workspaces?${params}`);
    const available=result.items.filter(w=>w.id!==workspace.value!.id&&w.initialized);
    sources.value=append?[...sources.value,...available]:available;sourceCursor.value=result.next_cursor;
  }
  async function chooseSource(item:Workspace){
    const result=await request<Resume>(`/api/v1/profiles/${person.value!.id}/workspaces/${item.id}/resume`);
    selectedSource.value=item;sourceResume.value=result;
  }
  async function copyResume(){
    if(busy.value||!selectedSource.value||!sourceResume.value)return;busy.value=true;
    try{
      const path=base()+'/resume/initialize',body={text:null,expected_version:0,source_workspace_id:selectedSource.value.id,expected_source_version:sourceResume.value.version};
      const result=await request<Resume>(path,'POST',body,operation(path+JSON.stringify(body)));
      text.value=result.text??'';saved.value=text.value;version.value=result.version;initialized.value=true;origin.value=result;
      copyMode.value=false;sourceResume.value=null;upload.value=null;preview.value=false;blocked.value=false;state.value='已保存';op=null;
    }catch(e){importError.value=fail(e);}finally{busy.value=false;}
  }
  async function restore(){
    nav.value='people';await list();const stored=localStorage.getItem('agentcv-m01-position');
    if(stored)try{const old=JSON.parse(stored);const item=await request<Workspace>(`/api/v1/profiles/${old.person.id}/workspaces/${old.workspace.id}`);person.value=old.person;await selectWorkspace(item);}
    catch{localStorage.removeItem('agentcv-m01-position');notice.value='上次位置不可用，请选择可用档案。';}
  }
  async function login(){if(busy.value)return;busy.value=true;loginError.value='';try{
    await request('/login','POST',new URLSearchParams({username:username.value,password:password.value}));password.value='';authenticated.value=true;
    if(expired.value){expired.value=false;if(workspace.value){const latest=await request<Resume>(base()+'/resume');blocked.value=latest.version!==version.value;}
      notice.value=blocked.value?'已重新登录，但服务端内容已变化。本页输入保留，请查看最新内容供核对。':'已重新登录，输入仍保留。请核对后主动保存，未自动重放操作。';}
    else await restore();
  }catch(e){loginError.value=fail(e);}finally{busy.value=false;}}
  onMounted(async()=>{window.addEventListener('beforeunload',e=>{if(dirty()||materialDirty.value){e.preventDefault();e.returnValue='';}});
    try{await request('/api/v1/session');authenticated.value=true;await restore();}catch(e){if(!(e instanceof ApiError&&e.status===401))loginError.value=fail(e);}ready.value=true;});
  const date=(value:string|null)=>value?new Date(value).toLocaleString():'待建立简历';
  const field=(label:string,value:string,update:(v:string)=>void,type='text',id?:string)=>h('label',[label,h('input',{id,type,value,onInput:(e:Event)=>update((e.target as HTMLInputElement).value)})]);
  function trap(e:KeyboardEvent){
    if(e.key==='Escape'&&!busy.value){e.preventDefault();if(!expired.value)close();}
    if(e.key==='Tab'){const all=Array.from((e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('input,textarea,button:not(:disabled)')),first=all[0],last=all[all.length-1];
      if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}
  }
  function loginForm(relogin=false){return h('form',{onSubmit:(e:Event)=>{e.preventDefault();void login();}},[
    h('h2',relogin?'登录已失效，请重新登录':'登录 AgentCV'),relogin?h('p','编辑仍保留，重新登录后不会自动保存。'):null,
    field('账号',username.value,v=>username.value=v),field('口令',password.value,v=>password.value=v,'password','password'),h('p',{role:'alert'},loginError.value),
    h('button',{type:'submit',disabled:busy.value},'登录'),relogin?button('复制未保存内容',()=>copy()):null]);}
  return()=>!ready.value?h('main','正在打开…'):!authenticated.value?h('main',{class:'login'},[loginForm()]):h('div',[
    h('header',[h('strong','AgentCV'),h('span',`${person.value?.display_name??'未选择人物'} / ${workspace.value?.name??'未选择工作区'}`),button('导航',()=>navOpen.value=!navOpen.value),
      button('退出登录',()=>safe(()=>leave(async()=>{await request('/logout','POST');authenticated.value=false;person.value=null;workspace.value=null;})))]),
    h('p',{role:'status',class:'notice'},notice.value),h('div',{class:'views'},[button('简历与资料',()=>view.value='document'),button('AI 对话',()=>view.value='ai')]),
    h('div',{class:'workbench'},[
      h('nav',{class:{open:navOpen.value}},[h('h2',nav.value==='people'?'个人档案':nav.value==='workspaces'?'工作区':'工作区内容'),
        nav.value!=='people'?button('← 返回上层',()=>safe(async()=>{nav.value=nav.value==='contents'?'workspaces':'people';search.value='';await list();})):null,
        nav.value!=='contents'?h('form',{onSubmit:(e:Event)=>{e.preventDefault();void safe(()=>list());}},[field('按名称搜索',search.value,v=>search.value=v),h('button',{type:'submit'},'搜索')]):null,
        nav.value==='people'?button('＋新建档案',()=>safe(()=>leave(async()=>open('person')))):nav.value==='workspaces'?button('＋新建工作区',()=>safe(()=>leave(async()=>open('workspace')))):null,
        ...(nav.value==='people'?people.value.map(p=>h('div',{class:'item'},[button(p.display_name,()=>safe(()=>selectPerson(p))),h('small',date(p.created_at))])):
          nav.value==='workspaces'?workspaces.value.map(w=>h('div',{class:'item'},[button(w.name,()=>safe(()=>selectWorkspace(w))),h('small',`${w.direction} · ${date(w.last_edited_at)} · 创建 ${date(w.created_at)}`)])):
          [button('工作区简历',()=>void safe(()=>leave(async()=>{backgroundMode.value=false;materialMode.value=false;nav.value='contents';view.value='document';navOpen.value=false;})))]),
        person.value?button('个人材料',()=>safe(()=>leave(async()=>{backgroundMode.value=false;materialMode.value=true;view.value='document';navOpen.value=false;}))):null,
        nav.value!=='contents'&&cursor.value?button('加载更多',()=>safe(()=>list(true))):null,
        nav.value==='people'&&!people.value.length?h('p','尚无档案或搜索无结果。'):null,
        nav.value==='workspaces'&&!workspaces.value.length?h('p','尚无工作区或搜索无结果。'):null]),
      h('main',{class:['document',view.value==='ai'?'mobile-hidden':'']},backgroundMode.value&&person.value?[
        h(backgrounds,{key:person.value.id,profileId:person.value.id,onAuth:()=>expired.value=true,onClose:()=>{backgroundMode.value=false;view.value='document';}})
      ]:materialMode.value&&person.value?[
        h(materials,{key:person.value.id+'-'+materialEpoch.value,profileId:person.value.id,workspaceId:workspace.value?.id,guard:(value:boolean)=>materialDirty.value=value,
          onClose:()=>{materialMode.value=false;nav.value=workspace.value?'contents':'workspaces';search.value='';navOpen.value=false;view.value='document';aiScopeRevision.value++;void safe(async()=>{if(!workspace.value)await list();await refreshReferenceCount();});},onAuth:()=>{expired.value=true;},onBusy:(value:boolean)=>materialBusy.value=value,onScope:(value:number)=>{referenceCount.value=value;aiScopeRevision.value++;}})
      ]:[h('h1',workspace.value?'工作区简历':person.value?'建立第一份目标简历':'创建个人档案开始使用'),
        workspace.value?[h('p',workspace.value.direction),!initialized.value?h('div',[
          h('p',preview.value?'预览并按需修正，然后建立工作区简历。':'待建立简历：上传或粘贴，或复制同人物其他工作区简历。'),
          button('导入工作区简历',()=>safe(()=>leave(async()=>{copyMode.value=false;sourceResume.value=null;upload.value=null;text.value='';preview.value=false;})),importing.value||busy.value),
          button('复制其他工作区简历',()=>safe(()=>leave(async()=>{copyMode.value=true;upload.value=null;text.value='';sourceResume.value=null;sourceQuery.value='';await listSources();})),importing.value||busy.value),
          !copyMode.value?h('label',['上传 TXT / DOCX / PDF',h('input',{type:'file',accept:'.txt,.docx,.pdf',disabled:importing.value||busy.value,onChange:(e:Event)=>{const input=e.target as HTMLInputElement;void safe(()=>uploadFile(input.files?.[0]));input.value='';}})]):null,
          importing.value?h('p','正在解析文件…'):null,
          upload.value?h('p',`${upload.value.original_name}：${upload.value.limitation||'已读取 UTF-8 文本，请核对并修正。'}`):null,
          h('p',{id:'import-error',role:'alert',tabindex:-1},importError.value),
          copyMode.value?h('div',[
            h('form',{onSubmit:(e:Event)=>{e.preventDefault();void safe(()=>listSources());}},[field('搜索来源工作区',sourceQuery.value,v=>sourceQuery.value=v),h('button',{type:'submit'},'查找来源')]),
            ...sources.value.map(w=>button(`${w.name} · ${w.direction}`,()=>safe(()=>chooseSource(w)))),
            sourceCursor.value?button('加载更多来源',()=>safe(()=>listSources(true))):null,
            !sources.value.length?h('p','当前没有可选来源，可继续查找或导入工作区简历。'):null,
            sourceResume.value?h('div',[h('p',`来源：${selectedSource.value?.name} → 目标：${workspace.value.name}。仅复制已保存文字，之后互不影响。`),
              h('label',['来源简历预览',h('textarea',{readonly:true,value:sourceResume.value.text})]),
              button('刷新来源预览',()=>safe(()=>chooseSource(selectedSource.value!))),button('确认复制并开始编辑',copyResume,busy.value),
              button('取消复制预览',()=>{sourceResume.value=null;selectedSource.value=null;})]):null]):null,
        ]):null,
          !copyMode.value||initialized.value?h('label',[initialized.value?'工作区简历正文':'粘贴简历文本',h('textarea',{value:text.value,disabled:importing.value||(busy.value&&!initialized.value),onInput:(e:Event)=>edit((e.target as HTMLTextAreaElement).value)})]):null,
          h('p',{role:'status'},state.value),initialized.value?[
            button(saving.value?'正在保存…':'保存 / 重试',()=>void save(),saving.value||blocked.value||expired.value),button('复制页面文本',()=>copy()),
            blocked.value?button('查看最新内容供核对',()=>safe(async()=>{const result=await request<Resume>(base()+'/resume');fallback.value=result.text??'';notice.value='下方为最新内容，本页编辑仍保留。';})):null,
            blocked.value?button('放弃本页编辑并载入最新内容',()=>{pending=async()=>{text.value=saved.value;blocked.value=false;await selectWorkspace(workspace.value!);};open('leave');}):null,
            h('details',[h('summary','来源信息'),
              origin.value?.source_kind==='UPLOAD'?h('div',[h('p',origin.value.origin?.original_name),h('a',{href:base()+'/resume/origin/file',target:'_blank',rel:'noopener'},'打开/下载导入文件'),
                origin.value.origin?.file_status!=='READY'?h('div',[h('p','简历文本已保存，来源文件未就绪。'),button('重试保存来源文件',()=>safe(async()=>{origin.value=await request<Resume>(base()+'/resume/origin/retry','POST',{},crypto.randomUUID());}))]):null]):
              origin.value?.source_kind==='COPY'?h('p',`复制自工作区：${origin.value.origin?.source_workspace_name??'来源不可用'}，后续独立编辑。`):h('p','由粘贴文本建立')])]:!copyMode.value?[
            preview.value?button('建立工作区简历',initialize,busy.value||importing.value||!text.value.trim()):button('预览并修正',()=>preview.value=true,!text.value.trim()),preview.value?button('返回粘贴',()=>preview.value=false):null]:null]:h('p','从导航创建或选择人物、工作区。'),
        fallback.value?h('label',['供手动复制或核对的文本',h('textarea',{readonly:true,value:fallback.value,onFocus:(e:FocusEvent)=>(e.target as HTMLTextAreaElement).select()})]):null]),
      h('aside',{class:['ai',view.value==='document'?'mobile-hidden':'']},workspace.value&&person.value?[
        h(conversation,{key:workspace.value.id,profileId:person.value.id,workspaceId:workspace.value.id,scopeRevision:aiScopeRevision.value,
          prepare:save,onAuth:()=>expired.value=true,onGuard:(guard:()=>Promise<boolean>)=>conversationGuard=guard,
          onBackgrounds:()=>safe(()=>leave(async()=>{backgroundMode.value=true;materialMode.value=false;view.value='document';})),
          onManage:()=>safe(()=>leave(async()=>{backgroundMode.value=false;materialMode.value=true;view.value='document';}))})
      ]:[h('h2','AI 对话'),h('p','请选择工作区后开始对话。')])]),
    expired.value?h('div',{class:'overlay'},[h('section',{role:'dialog','aria-modal':'true','aria-label':'重新登录',onKeydown:trap},[loginForm(true)])]):null,
    modal.value?h('div',{class:'overlay'},[h('section',{role:'dialog','aria-modal':'true','aria-label':modal.value==='leave'?'未保存内容':modal.value==='person'?'新建档案':'新建工作区',onKeydown:trap},[
      h('h2',modal.value==='leave'?'有未保存内容':modal.value==='person'?'新建档案':'新建工作区'),
      modal.value==='leave'?[h('p',initialized.value?'保存失败或存在未保存修改，明确放弃会丢失编辑。':'导入尚未完成，放弃后需要重新导入。'),button('留在当前页面',close),button('复制页面文本',()=>copy()),button('放弃未保存内容并继续',()=>safe(async()=>{const action=pending;modal.value=null;pending=null;text.value=saved.value;upload.value=null;sourceResume.value=null;preview.value=false;if(action)await action();}))]:
        h('form',{onSubmit:(e:Event)=>{e.preventDefault();void create();}},[modal.value==='workspace'?h('p',`所属人物：${person.value?.display_name}`):null,
          field(modal.value==='person'?'档案名称':'工作区名称',name.value,v=>name.value=v),modal.value==='workspace'?field('岗位方向',direction.value,v=>direction.value=v):null,
          h('p','允许同名，建议添加辨识信息；不会自动合并。'),h('p',{role:'alert'},error.value),button('取消',close,busy.value),h('button',{type:'submit',disabled:busy.value},'创建')])])]):null]);
}};