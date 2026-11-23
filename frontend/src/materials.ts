import { h, ref, onMounted, onBeforeUnmount, PropType, defineComponent } from 'vue';
import { request, ApiError } from './api';
type Material={id:string;name:string;text:string;row_version:number;text_version:number;original_name:string|null;file_status:string|null;limitation:string;references:string[];content_updated_at:string};
type Workspace={id:string;name:string;direction:string};
type Scope={version:number;items:{id:string;name:string;limitation:string}[]};
const btn=(label:string,action:()=>unknown,disabled=false)=>h('button',{type:'button',onClick:action,disabled},label);
export const materials=defineComponent({
  props:{profileId:{type:String,required:true},workspaceId:String,guard:{type:Function as PropType<(dirty:boolean)=>void>,required:true}},
  emits:['close','auth','busy','scope'],
  setup(props,{emit}){
    const items=ref<Material[]>([]),query=ref(''),more=ref(false),selected=ref<Material|null>(null),workspaces=ref<Workspace[]>([]);
    const adding=ref(false),editing=ref<null|'name'|'text'>(null),title=ref(''),text=ref(''),file=ref<File|null>(null),destinations=ref<string[]>([]);
    const message=ref(''),busy=ref(false),fallback=ref(''),scope=ref<Scope>({version:0,items:[]}),loading=ref(true);
    const targets=ref<Record<string,boolean>>({});
    const scopeVersions:Record<string,number>={};
    let pending:{signature:string;key:string}|null=null;
    const base=()=>`/api/v1/profiles/${props.profileId}`;
    const dirty=()=>busy.value||(adding.value?!!title.value||!!text.value||!!file.value:editing.value!==null||!!selected.value&&workspaces.value.some(w=>!!targets.value[w.id]!==selected.value!.references.includes(w.id)));
    function mark(){props.guard(dirty());emit('busy',busy.value);}
    function key(signature:string){if(!pending||pending.signature!==signature)pending={signature,key:crypto.randomUUID()};return pending.key;}
    function error(e:unknown){if(e instanceof ApiError&&(e.status===401||e.status===403))emit('auth');return e instanceof ApiError?e.message:'网络或服务暂不可用，输入仍保留。';}
    async function safe(action:()=>Promise<void>){try{await action();}catch(e){message.value=error(e);}}
    function canLeave(){if(busy.value){message.value='操作正在保存，请等待结果后切换。';return false;}return !dirty()||window.confirm('材料或参考设置尚未保存，明确放弃后将丢失本次输入。是否放弃？');}
    function reset(){adding.value=false;editing.value=null;title.value='';text.value='';file.value=null;pending=null;mark();}
    async function refresh(append=false){const result=await request<{items:Material[];has_more:boolean}>(`${base()}/materials?q=${encodeURIComponent(query.value)}&offset=${append?items.value.length:0}`);items.value=append?[...items.value,...result.items]:result.items;more.value=result.has_more;}
    async function loadWorkspaces(){
      let after:string|null=null;const result:Workspace[]=[];
      do{const page:{items:Workspace[];next_cursor:string|null}=await request(`${base()}/workspaces${after?'?after='+after:''}`);result.push(...page.items);after=page.next_cursor;}while(after);
      workspaces.value=result;
    }
    async function loadScope(){if(props.workspaceId){scope.value=await request<Scope>(`${base()}/workspaces/${props.workspaceId}/references`);emit('scope',scope.value.items.length);}}
    async function captureScopes(){for(const w of workspaces.value){const value=await request<Scope>(`${base()}/workspaces/${w.id}/references`);scopeVersions[w.id]=value.version;}}
    async function open(id:string){if(!canLeave())return;const value=await request<Material>(`${base()}/materials/${id}`);reset();selected.value=value;targets.value=Object.fromEntries(value.references.map(id=>[id,true]));await captureScopes();message.value='';}
    async function copy(){const value=editing.value==='text'||adding.value?text.value:selected.value?.text??'';try{await navigator.clipboard.writeText(value);message.value='已复制。';}catch{fallback.value=value;message.value='自动复制不可用，请手动复制下方文字。';}}
    async function applyReference(w:string,m:string,add:boolean){
      const result=await request<Scope>(`${base()}/workspaces/${w}/references/${m}`,add?'PUT':'DELETE',{expected_scope_version:scopeVersions[w]??0},key(`${w}/${m}/${add}/${scopeVersions[w]??0}`));
      scopeVersions[w]=result.version;return result;
    }
    async function create(keep=false){
      if(busy.value)return;if(!title.value.trim()){message.value='请填写材料名称。';return;}
      busy.value=true;mark();
      try{
        let body:Record<string,unknown>={name:title.value,text:text.value,keep_duplicate:keep};
        if(file.value){const bytes=new Uint8Array(await file.value.arrayBuffer());let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);body={name:file.value.name,base64:btoa(binary),keep_duplicate:keep};}
        const created=await request<Material>(`${base()}/materials`,'POST',body,key(JSON.stringify(body)));
        const chosen=[...destinations.value];reset();selected.value=null;
        // From here onward the material is committed even if a refresh/reference request fails.
        message.value='个人材料已保存，正在核对原件和参考范围；请勿重复上传。';
        const failed:string[]=[];
        for(const w of chosen){try{await applyReference(w,created.id,true);}catch{failed.push(w);}}
        await refresh();await loadScope();selected.value=await request<Material>(`${base()}/materials/${created.id}`);
        targets.value=Object.fromEntries(selected.value.references.map(id=>[id,true]));
        for(const w of failed)targets.value[w]=true;
        message.value=failed.length?'材料已保存，部分参考设置失败；勾选已保留，可重试，无需重新上传。':'个人材料已保存；参考范围已按选择设置，未调用 AI。';
      }catch(e){
        if(e instanceof ApiError&&e.code==='DUPLICATE'&&!keep){busy.value=false;if(window.confirm(e.message)){await create(true);return;}}
        message.value=error(e);
      }finally{busy.value=false;mark();}
    }
    async function saveEdit(){
      if(busy.value||!selected.value||!editing.value)return;busy.value=true;mark();
      const body={expected_version:selected.value.row_version,...editing.value==='name'?{name:title.value}:{text:text.value}};
      try{selected.value=await request<Material>(`${base()}/materials/${selected.value.id}`,'PATCH',body,key(JSON.stringify(body)));reset();await refresh();await loadScope();message.value='已保存。材料文本编辑不改变原文件、简历或背景。';}
      catch(e){message.value=error(e);}finally{busy.value=false;mark();}
    }
    async function saveTargets(){
      if(!selected.value||busy.value)return;busy.value=true;mark();
      try{
        const old=selected.value.references;
        for(const w of workspaces.value){const add=!!targets.value[w.id];if(add!==old.includes(w.id))await applyReference(w.id,selected.value.id,add);}
        selected.value=await request<Material>(`${base()}/materials/${selected.value.id}`);await loadScope();
        message.value='参考范围已保存，未触发 AI；停止参考不删除材料，也不清除旧聊天中的信息。';
      }catch(e){message.value='材料仍保留，参考设置未全部生效。'+error(e);selected.value=await request<Material>(`${base()}/materials/${selected.value!.id}`).catch(()=>selected.value);}
      finally{busy.value=false;mark();}
    }
    onMounted(()=>void safe(async()=>{await refresh();await loadWorkspaces();await captureScopes();await loadScope();loading.value=false;}));
    onBeforeUnmount(()=>{props.guard(false);emit('busy',false);});
    const input=(label:string,value:string,change:(v:string)=>void,area=false)=>h('label',[label,h(area?'textarea':'input',{value,disabled:busy.value,onInput:(e:Event)=>{change((e.target as HTMLInputElement).value);mark();}})]);
    function chooseFile(value:File|undefined){if(!value)return;if(value.size>262144||value.size===0){message.value='文件为空或超过 256 KiB。';return;}if(!/\.(txt|docx|pdf)$/i.test(value.name)){message.value='仅支持 TXT、DOCX、文本层 PDF。';return;}file.value=value;title.value=value.name;mark();}
    return()=>h('div',{class:'materials'},[
      h('h1','个人材料'),h('p',`属于当前人物；个人材料是补充参考，保存不等于确认经历。${props.workspaceId?`本区参考 ${scope.value.items.length} 份。`:'未选择工作区也可管理。'}`),
      btn(props.workspaceId?'返回工作区简历':'返回工作区列表',()=>{if(canLeave())emit('close');},busy.value),btn('＋添加个人材料',()=>{if(canLeave()){reset();selected.value=null;adding.value=true;destinations.value=[];message.value='';}},busy.value),
      h('p',{role:'status'},message.value),
      h('div',{class:'materials-layout'},[
        h('section',[
          h('form',{onSubmit:(e:Event)=>{e.preventDefault();void safe(()=>refresh());}},[input('搜索材料名称',query.value,v=>query.value=v),h('button',{type:'submit'},'搜索材料'),btn('清空搜索',()=>{query.value='';void safe(()=>refresh());})]),
          loading.value?h('p','正在加载…'):!items.value.length?h('p',query.value?'搜索无结果，可清空搜索。':'尚无个人材料，可添加。'):null,
          ...items.value.map(m=>h('div',{class:'item'},[btn(m.name,()=>safe(()=>open(m.id)),busy.value),scope.value.items.some(r=>r.id===m.id)?h('small','本区参考中'):null,m.limitation?h('small','部分提取 / 提取范围有限'):null])),
          more.value?btn('加载更多材料',()=>safe(()=>refresh(true))):null]),
        h('section',[
          adding.value?h('form',{onSubmit:(e:Event)=>{e.preventDefault();void create();}},[
            h('h2','添加个人材料'),h('p','参考去向默认不选，可多选同人物工作区。文件将由服务端解析，不要求整份检查确认。'),
            file.value?h('p',`文件：${file.value.name}（先按文件名保存，之后可重命名）`):input('材料名称',title.value,v=>title.value=v),
            h('label',['选择材料文件',h('input',{type:'file',accept:'.txt,.docx,.pdf',disabled:busy.value,onChange:(e:Event)=>chooseFile((e.target as HTMLInputElement).files?.[0])})]),
            file.value?btn('改用粘贴文本',()=>{file.value=null;mark();}):input('材料粘贴文本',text.value,v=>text.value=v,true),
            h('fieldset',[h('legend','参考去向（可不选）'),...workspaces.value.map(w=>h('label',[h('input',{type:'checkbox',checked:destinations.value.includes(w.id),onChange:(e:Event)=>{destinations.value=(e.target as HTMLInputElement).checked?[...destinations.value,w.id]:destinations.value.filter(id=>id!==w.id);}}),`${w.name} · ${w.direction}`]))]),
            h('button',{type:'submit',disabled:busy.value},busy.value?'正在保存…':'保存个人材料'),btn('取消添加',()=>{if(canLeave())reset();},busy.value),btn('复制输入',copy)]):null,
          selected.value&&!adding.value?h('div',[
            h('h2',selected.value.name),h('p',`正文更新：${new Date(selected.value.content_updated_at).toLocaleString()}`),
            selected.value.limitation?h('p',selected.value.limitation):null,
            editing.value?[
              input(editing.value==='name'?'新的材料名称':'编辑材料文本',editing.value==='name'?title.value:text.value,v=>{if(editing.value==='name')title.value=v;else text.value=v;},editing.value==='text'),
              btn('保存材料修改',saveEdit,busy.value),btn('取消修改',reset,busy.value),btn('复制当前内容',copy),
              btn('查看最新材料供核对',()=>safe(async()=>{const latest=await request<Material>(`${base()}/materials/${selected.value!.id}`);fallback.value=latest.text;message.value=`最新名称：${latest.name}；本页输入保留。若需重新编辑，请先复制输入，再取消修改并重新打开材料。`;}))]:[
              h('label',['材料正文',h('textarea',{readonly:true,value:selected.value.text})]),
              btn('重命名材料',()=>{editing.value='name';title.value=selected.value!.name;mark();}),btn('编辑材料文本',()=>{editing.value='text';text.value=selected.value!.text;mark();}),btn('复制材料文本',copy)],
            selected.value.original_name?h('div',[h('p',`原文件：${selected.value.original_name}`),h('a',{href:`${base()}/materials/${selected.value.id}/file`,target:'_blank',rel:'noopener'},'打开/下载材料原件'),
              selected.value.file_status!=='READY'?btn('重试保存材料原件',()=>safe(async()=>{selected.value=await request<Material>(`${base()}/materials/${selected.value!.id}/file/retry`,'POST',{},crypto.randomUUID());message.value=selected.value.file_status==='READY'?'原件已就绪。':'文本已保存，原件仍未就绪。';})):null]):h('p','由粘贴文本创建，无原件。'),
            h('fieldset',[h('legend','调整参考去向'),...workspaces.value.map(w=>h('label',[h('input',{type:'checkbox',disabled:busy.value,checked:!!targets.value[w.id],onChange:(e:Event)=>{targets.value[w.id]=(e.target as HTMLInputElement).checked;mark();}}),`${w.name} · ${w.direction}`]))]),
            btn('保存参考去向',()=>void saveTargets(),busy.value),
            btn('刷新参考状态',()=>safe(async()=>{await captureScopes();selected.value=await request<Material>(`${base()}/materials/${selected.value!.id}`);await loadScope();message.value='已刷新状态，勾选仍保留，请核对后保存。';mark();}),busy.value),
            h('p','同一材料由多个工作区共享。正文保存后，各引用区后续使用新文本；不改原件、工作区简历或背景，不触发 AI。')]):null,
          fallback.value?h('label',['复制兜底 / 最新内容',h('textarea',{readonly:true,value:fallback.value,onFocus:(e:FocusEvent)=>(e.target as HTMLTextAreaElement).select()})]):null,
        ]),
      ]),
    ]);
  },
});