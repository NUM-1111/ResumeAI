import { createApp, defineComponent, h, onMounted, reactive, ref } from 'vue';
import { Editor, type Resume } from './editor';
import './style.css';
interface Profile { id: string; displayName: string }
interface Workspace { id: string; name: string; direction: string; rowVersion: number }
interface Material { id: string; name: string; checkState: string; rowVersion: number; extractedText?: string; confirmedText?: string }
interface Page<T> { items: T[]; nextCursor: string | null }
class ApiError extends Error { constructor(message: string, public status: number, public code: string) { super(message); } }
// Retain failed mutation identities within this page, including a lost response.
const retryKeys = new Map<string,string>();
async function api<T>(path: string, method = 'GET', body?: object, key?: string): Promise<T> {
 const fingerprint=method+path+JSON.stringify(body);
 if(method!=='GET'&&!key){key=retryKeys.get(fingerprint)??crypto.randomUUID();retryKeys.set(fingerprint,key);}
 const headers: Record<string,string> = {};
 if(method !== 'GET') {
  const r=await fetch('/api/v1/csrf',{cache:'no-store',credentials:'same-origin'});if(!r.ok) throw Error('无法取得访问凭据，请保留文本');
  const c=await r.json();headers[c.headerName]=c.token;headers['Content-Type']='application/json';headers['Idempotency-Key']=key!;
 }
 const r=await fetch('/api/v1'+path,{method,headers,credentials:'same-origin',cache:'no-store',body:body?JSON.stringify(body):undefined});
 if(!r.ok){const e=await r.json().catch(()=>({}));throw new ApiError(e.message??(r.status===401?'会话失效，请复制文本再重新登录':`请求失败 HTTP ${r.status}，未确认成功`),r.status,e.code??'HTTP_ERROR');}
 const result=await r.json() as T;retryKeys.delete(fingerprint);return result;
}
createApp(defineComponent({setup(){
 const profiles=ref<Profile[]>([]),workspaces=ref<Workspace[]>([]),materials=ref<Material[]>([]);
 const profile=ref<Profile|null>(null),workspace=ref<Workspace|null>(null),material=ref<Material|null>(null);
 const editor=reactive(new Editor());const initialized=ref(false),busy=ref(false),error=ref(''),message=ref(''),fallback=ref(false);
 const profileName=ref(''),workspaceName=ref(''),direction=ref(''),importName=ref('简历.txt'),importText=ref(''),uploaded=ref<string|null>(null);
 const sourceMaterial=ref(''),sourceWorkspace=ref('');let timer:ReturnType<typeof setTimeout>|undefined;let sending=false;
 const prefix=()=>`/profiles/${profile.value!.id}`;
 const resumePath=()=>prefix()+`/workspaces/${workspace.value!.id}/resume`;
 async function all<T>(path:string):Promise<T[]>{const result:T[]=[];let cursor:string|null=null;do{const page:Page<T>=await api(path+'?limit=100'+(cursor?'&after='+cursor:''));result.push(...page.items);cursor=page.nextCursor;}while(cursor);return result;}
 async function task(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await action();}catch(e){error.value=e instanceof Error?e.message:'操作失败，未确认成功';}finally{busy.value=false;}}
 async function save():Promise<boolean>{if(!initialized.value)return true;if(sending)return false;sending=true;try{return await editor.save((body,key)=>api<Resume>(resumePath(),'PUT',body,key));}finally{sending=false;}}
 async function flush(){while(editor.dirty&&editor.state!=='CONFLICT'){if(sending)return false;if(await save())break;}return !editor.dirty;}
 async function leave(){clearTimeout(timer);if(sending){error.value='正在保存，请等待结果后切换';return false;}
  try{await flush();}catch(e){error.value=e instanceof Error?e.message:'保存失败';}
  if(editor.dirty&&!window.confirm('仍有未保存文本。建议取消并复制兜底。确定放弃页面编辑并离开吗？'))return false;
  if(importDirty()&&!window.confirm('材料文本尚未保存或检查修正尚未确认。取消可继续编辑；确定将放弃这些页面内容。'))return false;return true;
 }
 function importDirty(){return material.value ? material.value.checkState==='UNCHECKED'&&importText.value!==material.value.extractedText : importText.value.length>0||uploaded.value!==null;}
 function resetImport(){material.value=null;importText.value='';uploaded.value=null;sourceMaterial.value='';sourceWorkspace.value='';}
 async function chooseProfile(p:Profile){if(!await leave())return;const ws=await all<Workspace>(`/profiles/${p.id}/workspaces`),ms=await all<Material>(`/profiles/${p.id}/material-imports`);profile.value=p;workspace.value=null;workspaces.value=ws;materials.value=ms;initialized.value=false;editor.load({});resetImport();}
 async function chooseWorkspace(w:Workspace){if(!await leave())return;const r=await api<Resume>(prefix()+`/workspaces/${w.id}/resume`);workspace.value=w;editor.load(r);initialized.value=!!r.initialized;resetImport();}
 function schedule(){clearTimeout(timer);timer=setTimeout(()=>{if(editor.state==='CONFLICT')return;if(sending||busy.value){schedule();return;}void task(async()=>{await save();if(editor.state==='DIRTY')schedule();});},900);}
 function edit(value:string){editor.edit(value);schedule();}
 async function refreshMaterials(){materials.value=await all<Material>(prefix()+'/material-imports');}
 async function importMaterial(keepDuplicate=false){try{material.value=await api<Material>(prefix()+'/material-imports','POST',{name:importName.value,...(uploaded.value===null?{text:importText.value}:{base64:uploaded.value}),keepDuplicate});importText.value=material.value.extractedText??'';uploaded.value=null;await refreshMaterials();message.value='已保存待检查材料，尚未建立当前稿';}catch(e){if(e instanceof ApiError&&e.code==='DUPLICATE'&&!keepDuplicate&&window.confirm(e.message+'。确定保留两份吗？'))await importMaterial(true);else throw e;}}
 async function openMaterial(id:string){material.value=await api<Material>(prefix()+'/material-imports/'+id);importText.value=material.value.confirmedText??material.value.extractedText??'';uploaded.value=null;}
 async function confirmMaterial(){material.value=await api<Material>(prefix()+`/material-imports/${material.value!.id}/confirm`,'POST',{text:importText.value,expectedVersion:material.value!.rowVersion});sourceMaterial.value=material.value.id;await refreshMaterials();message.value='文本已检查确认可用，不代表确认经历真实';}
 async function initialize(type:string,id:string,revision:number){const r=await api<Resume>(resumePath()+'/initialize','POST',{sourceType:type,sourceId:id,expectedSourceRevision:revision});editor.load(r);initialized.value=true;message.value='已建立独立当前稿并保存';}
 async function copy(){try{await navigator.clipboard.writeText(editor.text);message.value='已复制页面当前文本，保存状态未改变';}catch{fallback.value=true;error.value='剪贴板不可用，请在兜底文本框全选复制';}}
 async function fileSelected(e:Event){const file=(e.target as HTMLInputElement).files?.[0];if(!file)return;if(!file.name.toLowerCase().endsWith('.txt')||file.size>262144)throw Error('仅支持不超过256 KiB的UTF-8 .txt');const bytes=new Uint8Array(await file.arrayBuffer());try{importText.value=new TextDecoder('utf-8',{fatal:true}).decode(bytes);}catch{throw Error('文件不是有效UTF-8，请转换或粘贴文本');}let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);uploaded.value=btoa(binary);importName.value=file.name;material.value=null;}
 onMounted(()=>{void task(async()=>{profiles.value=await all<Profile>('/profiles');});window.addEventListener('beforeunload',e=>{if(editor.dirty||importDirty()){e.preventDefault();e.returnValue='';}});});
 const button=(label:string,action:()=>Promise<void>,disabled=false)=>h('button',{disabled:busy.value||disabled,onClick:()=>task(action)},label);
 const input=(label:string,value:string,setter:(v:string)=>void,max=100)=>h('label',[label,h('input',{value,maxlength:max,onInput:(e:Event)=>setter((e.target as HTMLInputElement).value)})]);
 const states={CLEAN:'已保存',DIRTY:'未保存',SAVING:'保存中',FAILED:'保存失败 · 未保存',CONFLICT:'版本冲突 · 未保存'};
 return ()=>h('div',{class:'shell'},[
 h('header',[h('h1','AgentCV · 文本简历工作台'),h('p','S01：仅合成材料验证。模型、JD分析、删除恢复未实现。'),button('退出登录',async()=>{if(!await leave())return;const c=await api<{token:string;headerName:string}>('/csrf');const r=await fetch('/logout',{method:'POST',headers:{[c.headerName]:c.token}});if(!r.ok)throw Error('退出失败');window.location.assign('/login?logout');})]),
 error.value?h('p',{role:'alert'},error.value):null,message.value?h('p',{role:'status'},message.value):null,
 h('div',{class:'columns'},[
 h('aside',[h('h2','个人档案'),...profiles.value.map(p=>button((profile.value?.id===p.id?'● ':'')+p.displayName,()=>chooseProfile(p))),input('新档案姓名',profileName.value,v=>profileName.value=v),button('创建档案',async()=>{if(!await leave())return;const p=await api<Profile>('/profiles','POST',{displayName:profileName.value});profiles.value=await all<Profile>('/profiles');await chooseProfile(p);}),
 profile.value?h('section',[h('h2',`${profile.value.displayName} · 工作区`),...workspaces.value.map(w=>button((workspace.value?.id===w.id?'● ':'')+w.name+' / '+w.direction,()=>chooseWorkspace(w))),input('工作区名称',workspaceName.value,v=>workspaceName.value=v),input('岗位方向',direction.value,v=>direction.value=v,200),button('创建独立工作区',async()=>{if(!await leave())return;const w=await api<Workspace>(prefix()+'/workspaces','POST',{name:workspaceName.value,direction:direction.value});workspaces.value=await all<Workspace>(prefix()+'/workspaces');await chooseWorkspace(w);})]):null]),
 h('main',[h('h2',workspace.value?`${profile.value?.displayName} / ${workspace.value.name}`:'请选择档案与工作区'),workspace.value?h('p',workspace.value.direction):null,
 initialized.value?h('section',[h('p',{role:'status'},`${states[editor.state]} · 服务端修订 v${editor.revision}`),h('textarea',{'aria-label':'当前简历',rows:24,value:editor.text,onInput:(e:Event)=>edit((e.target as HTMLTextAreaElement).value)}),button('立即保存 / 重试',async()=>{await flush();},sending||editor.state==='CONFLICT'),button('复制页面文本',copy),button('重新打开服务端当前稿',async()=>{await chooseWorkspace(workspace.value!);}),fallback.value?h('textarea',{'aria-label':'复制兜底文本',readonly:true,value:editor.text,rows:8,onFocus:(e:FocusEvent)=>(e.target as HTMLTextAreaElement).select()}):null,h('p','保存失败保留页面文本；关闭页面后不保证恢复未保存内容。')]):h('p','待初始化：先确认材料，或复制同档案已有工作区。'),
 workspace.value?h('details',[h('summary','修改工作区名称 / 方向'),input('名称',workspace.value.name,v=>workspace.value!.name=v),input('方向',workspace.value.direction,v=>workspace.value!.direction=v,200),button('保存工作区属性',async()=>{const w=workspace.value!;await api(prefix()+'/workspaces/'+w.id,'PATCH',{name:w.name,direction:w.direction,expectedVersion:w.rowVersion});workspaces.value=await all<Workspace>(prefix()+'/workspaces');workspace.value=workspaces.value.find(x=>x.id===w.id)!;})]):null]),
 h('aside',profile.value?[
 h('h2','档案简历材料'),h('p','检查文本可用 ≠ 确认经历真实。材料不会自动进入当前稿。'),...materials.value.map(m=>button(`${m.name} · ${m.checkState==='CONFIRMED'?'已确认':'待检查'}`,()=>openMaterial(m.id))),button('新建文本导入',async()=>{resetImport();}),input('材料名称',importName.value,v=>importName.value=v,200),h('input',{type:'file',accept:'.txt,text/plain','aria-label':'上传UTF-8文本',onChange:(e:Event)=>task(()=>fileSelected(e))}),h('textarea',{'aria-label':'材料检查文本',rows:12,value:importText.value,readonly:material.value?.checkState==='CONFIRMED',onInput:(e:Event)=>{importText.value=(e.target as HTMLTextAreaElement).value;uploaded.value=null;}}),!material.value?button('保存为待检查材料',()=>importMaterial()):material.value.checkState==='UNCHECKED'?button('确认检查后的文本',confirmMaterial):h('p','已确认材料保持不变；新稿请新建导入。'),
 workspace.value&&!initialized.value?h('section',[h('h3','选择起始稿'),h('select',{'aria-label':'选择已确认材料',value:sourceMaterial.value,onChange:(e:Event)=>sourceMaterial.value=(e.target as HTMLSelectElement).value},[h('option',{value:''},'选择本档案已确认材料'),...materials.value.filter(m=>m.checkState==='CONFIRMED').map(m=>h('option',{value:m.id},m.name))]),button('以所选材料建立当前稿',async()=>{const m=materials.value.find(m=>m.id===sourceMaterial.value);if(!m)throw Error('请选择已确认材料');await initialize('MATERIAL_REVISION',m.id,m.rowVersion);}),h('select',{'aria-label':'复制来源工作区',value:sourceWorkspace.value,onChange:(e:Event)=>sourceWorkspace.value=(e.target as HTMLSelectElement).value},[h('option',{value:''},'复制本档案其他区当前稿'),...workspaces.value.filter(w=>w.id!==workspace.value?.id).map(w=>h('option',{value:w.id},w.name))]),button('复制为独立当前稿',async()=>{if(!sourceWorkspace.value)throw Error('请选择来源区');const r=await api<Resume>(prefix()+`/workspaces/${sourceWorkspace.value}/resume`);if(!r.initialized)throw Error('来源区尚无当前稿');await initialize('WORKSPACE_CURRENT',sourceWorkspace.value,r.revision!);})]):null
 ]:[h('p','创建或选择档案后可导入中文文本。')])])]);
}})).mount('#app');