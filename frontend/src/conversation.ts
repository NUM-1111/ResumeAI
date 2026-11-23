import { defineComponent,h,ref,onMounted,onBeforeUnmount,watch,PropType } from 'vue';
import { request,ApiError } from './api';
type Scope={resume_version:number;resume_available:boolean;scope_version:number;materials:{id:string;name:string;text_version:number;limitation:string}[]};
type Task={id:string;message:string;answer:string|null;execution_status:string;record_status:string;error_code:string|null;model:string;basis_changed?:boolean;created_at?:string};
export const conversation=defineComponent({
  props:{profileId:{type:String,required:true},workspaceId:{type:String,required:true},prepare:{type:Function as PropType<()=>Promise<boolean>>,required:true},scopeRevision:Number},
  emits:['auth','manage','guard'],
  setup(props,{emit}){
    const scope=ref<Scope|null>(null),configured=ref(false),text=ref(''),reference=ref(true),tasks=ref<Task[]>([]),notice=ref(''),busy=ref(false);
    const referenceDialog=ref<HTMLDialogElement|null>(null),referenceButton=ref<HTMLButtonElement|null>(null);
    const available=ref<{id:string;name:string;limitation:string}[]>([]),referenceLoading=ref(false),referenceSaving=ref(false),referenceError=ref('');
    const state=ref({version:0,epoch:0,boundary_at:'-infinity'}),draftVersion=ref(0),draftSaved=ref(''),draftStatus=ref(''),draftConflict=ref(false),menu=ref(false),historyCount=ref(50),reviewRequired=ref(false);
    let draftTimer:ReturnType<typeof setTimeout>|undefined,draftFlight:Promise<boolean>|null=null,draftOp:{signature:string;key:string}|null=null;
    const hasMore=ref(false);
    let timer:ReturnType<typeof setTimeout>|undefined,alive=true,operation:{body:string;key:string}|null=null;
    const base=()=>`/api/v1/profiles/${props.profileId}/workspaces/${props.workspaceId}`;
    const active=()=>tasks.value.some(t=>['ACCEPTED','CALLING'].includes(t.execution_status)||t.record_status==='SAVE_FAILED');
    function failure(e:unknown){if(e instanceof ApiError&&(e.status===401||e.status===403))emit('auth');return e instanceof ApiError?e.message:'暂时无法获取状态，稍后继续查询原请求；输入仍保留。';}
    async function refreshScope(){scope.value=await request<Scope>(base()+'/ai-scope');}
    async function openReferences(){
      referenceDialog.value?.showModal();referenceError.value='';referenceLoading.value=true;
      try{
        await refreshScope();
        const rows:typeof available.value=[];let offset=0,more=true;
        while(more){
          const result=await request<{items:typeof available.value;has_more:boolean}>(`/api/v1/profiles/${props.profileId}/materials?offset=${offset}`);
          rows.push(...result.items);offset+=result.items.length;more=result.has_more;
          if(more&&!result.items.length)throw new Error('Invalid pagination');
        }
        available.value=rows;
      }catch(e){referenceError.value=failure(e);}finally{referenceLoading.value=false;}
    }
    function closeReferences(){if(referenceSaving.value)return;referenceDialog.value?.close();referenceButton.value?.focus();}
    async function selectMaterial(id:string,add:boolean){
      if(referenceSaving.value||!scope.value)return;
      referenceSaving.value=true;referenceError.value='';
      try{
        await request(base()+'/references/'+id,add?'PUT':'DELETE',{expected_scope_version:scope.value.scope_version},crypto.randomUUID());
        await refreshScope();notice.value='参考范围已更新，未调用 AI；旧回答保持不变。';
      }catch(e){referenceError.value=failure(e);}
      finally{referenceSaving.value=false;}
    }
    async function poll(){
      try{
        const result=await request<{requests:Task[];state?:typeof state.value;has_more?:boolean}>(base()+'/conversation?limit='+Math.min(1000,historyCount.value));
        if(!alive)return;
        const values=result.requests;
        for(let i=0;i<values.length;i++)if(['ACCEPTED','CALLING'].includes(values[i].execution_status)||values[i].record_status==='SAVE_FAILED')
          values[i]=await request<Task>(base()+'/requests/'+values[i].id);
        if(alive){
          const previouslyKnown=tasks.value;
          if(result.state&&result.state.epoch!==state.value.epoch){
            clearTimeout(draftTimer);
            if(text.value){draftConflict.value=true;draftStatus.value='对话已在别页重置或清空，本页文字保留，核对前暂停草稿保存。';}
          }
          if(result.state&&result.state.version!==state.value.version&&text.value&&
              (result.state.epoch!==state.value.epoch||values.some(task=>!previouslyKnown.some(old=>old.id===task.id))))
            {reviewRequired.value=true;notice.value='其他页面已改变对话，已同步展示。请核对后再发送，输入仍保留。';}
          const older=tasks.value.filter(task=>!values.some(newTask=>newTask.id===task.id));
          tasks.value=result.state&&result.state.epoch!==state.value.epoch?values:[...values,...older];hasMore.value=result.has_more??false;
          if(result.state)state.value=result.state;
        }
      }catch(e){if(alive)notice.value=failure(e);}
      finally{if(alive)timer=setTimeout(poll,1500);}
    }
    async function loadOlder(){
      try{
        const result=await request<{requests:Task[];has_more:boolean}>(base()+'/conversation?limit=50&offset='+tasks.value.length);
        tasks.value=[...tasks.value,...result.requests.filter(task=>!tasks.value.some(old=>old.id===task.id))];hasMore.value=result.has_more;
      }catch(e){notice.value=failure(e);}
    }
    async function send(){
      if(busy.value||active()||reviewRequired.value||!text.value.trim()||!scope.value)return;
      busy.value=true;notice.value='';
      try{
        if(draftFlight)await draftFlight;
        if(reference.value&&!await props.prepare()){notice.value='简历未保存，本条消息未发送。';return;}
        // Preserve scope/version conditions unless user explicitly refreshes; resume save is intentional.
        if(reference.value){const latest=await request<{version:number}>(base()+'/resume');scope.value.resume_version=latest.version;}
        const body={message:text.value,reference_resume:reference.value,expected_resume_version:scope.value.resume_version,
          expected_scope_version:scope.value.scope_version,material_versions:Object.fromEntries(scope.value.materials.map(m=>[m.id,m.text_version])),
          expected_conversation_version:state.value.version,expected_context_epoch:state.value.epoch};
        const signature=JSON.stringify(body);
        if(!operation||operation.body!==signature)operation={body:signature,key:crypto.randomUUID()};
        const submitted=text.value;
        const result=await request<Task>(base()+'/conversation','POST',body,operation.key);
        tasks.value=[result,...tasks.value.filter(t=>t.id!==result.id)];operation=null;
        if(text.value===submitted){text.value='';void saveDraft();}
        else if(text.value!==draftSaved.value){void saveDraft();}
      }catch(e){notice.value=failure(e);if(e instanceof ApiError&&e.code==='CONVERSATION_CHANGED')reviewRequired.value=true;}finally{busy.value=false;}
    }
    async function persist(task:Task){try{const updated=await request<Task>(base()+'/requests/'+task.id+'/persist','POST',{},crypto.randomUUID());tasks.value=tasks.value.map(t=>t.id===task.id?updated:t);}catch(e){notice.value=failure(e);}}
    async function saveDraft():Promise<boolean>{
      clearTimeout(draftTimer);
      if(draftFlight){const ok=await draftFlight;return ok&&text.value!==draftSaved.value?saveDraft():ok;}
      if(draftConflict.value)return false;if(text.value===draftSaved.value)return true;
      const value=text.value,body={text:value,expected_version:draftVersion.value,expected_epoch:state.value.epoch};
      const signature=JSON.stringify(body);if(!draftOp||draftOp.signature!==signature)draftOp={signature,key:crypto.randomUUID()};
      draftStatus.value='正在保存草稿…';
      draftFlight=(async()=>{try{
        const result=await request<{text:string;version:number}>(base()+'/draft','PUT',body,draftOp!.key);
        draftVersion.value=result.version;draftSaved.value=value;draftOp=null;draftStatus.value=text.value===value?'草稿已保存':'草稿有未保存修改';return true;
      }catch(e){draftConflict.value=e instanceof ApiError&&e.code==='VERSION_CONFLICT';draftStatus.value=failure(e)+' 刷新或关闭可能丢失未保存文字；仍可发送。';return false;}})();
      const ok=await draftFlight;draftFlight=null;return ok;
    }
    function editDraft(value:string){text.value=value;draftStatus.value='草稿未保存';clearTimeout(draftTimer);if(!draftConflict.value)draftTimer=setTimeout(()=>void saveDraft(),600);}
    async function beforeLeave():Promise<boolean>{
      if(text.value!==draftSaved.value&&!await saveDraft())return window.confirm('输入草稿未保存。请先复制留存，确认放弃未保存草稿并切换？');
      if(tasks.value.some(t=>t.record_status==='SAVE_FAILED'))return window.confirm('回答记录尚未保存，请先复制。是否明确离开？');
      return true;
    }
    async function resetContext(clear:boolean){
      if(active()||busy.value)return;
      const prompt=clear?'清空本区对话记录和输入草稿，界面无法恢复，不另存旧会话。简历、材料和背景不变。确认清空？':'后续不再携带此前聊天，旧消息仍可回看；简历、材料和背景范围不变。确认继续？';
      if(!window.confirm(prompt))return;busy.value=true;
      try{
        if(draftFlight)await draftFlight;clearTimeout(draftTimer);
        const result=await request<typeof state.value>(base()+(clear?'/conversation/clear':'/context-boundaries'),'POST',{expected_version:state.value.version,expected_epoch:state.value.epoch},crypto.randomUUID());
        state.value=result;menu.value=false;reviewRequired.value=false;
        if(clear){const draft=await request<{text:string;version:number}>(base()+'/draft');text.value='';draftSaved.value='';draftVersion.value=draft.version;draftConflict.value=false;tasks.value=[];draftStatus.value='草稿已清空';}
        notice.value=clear?'记录和草稿已清空。':'已建立分隔点，后续不携带此前聊天。';
      }catch(e){notice.value=failure(e);}finally{busy.value=false;}
    }
    function beforeBoundary(task:Task){return state.value.boundary_at!=='-infinity'&&!!task.created_at&&new Date(task.created_at).getTime()<=new Date(state.value.boundary_at).getTime();}
    async function copy(answer:string){try{await navigator.clipboard.writeText(answer);notice.value='已复制回答。';}catch{notice.value='自动复制不可用，请选中回答文字手动复制。';}}
    const unload=(event:BeforeUnloadEvent)=>{if(text.value!==draftSaved.value||tasks.value.some(t=>t.record_status==='SAVE_FAILED')){event.preventDefault();event.returnValue='';}};
    onMounted(async()=>{window.addEventListener('beforeunload',unload);try{const model=await request<{configured:boolean}>('/api/v1/model');configured.value=model.configured;await refreshScope();
        state.value=await request<typeof state.value>(base()+'/conversation-state');const draft=await request<{text:string;version:number}>(base()+'/draft');
      draftVersion.value=draft.version;if(!text.value){text.value=draft.text;draftSaved.value=draft.text;}
    }catch(e){notice.value=failure(e);}void poll();});
    watch(()=>props.scopeRevision,()=>{void refreshScope().catch(e=>notice.value=failure(e));});
    onBeforeUnmount(()=>{alive=false;clearTimeout(timer);clearTimeout(draftTimer);window.removeEventListener('beforeunload',unload);});
    // Parent invokes this before a real workspace/person switch; navigation browsing is separate.
    emit('guard',beforeLeave);
    return()=>h('section',{class:'conversation'},[
      h('div',{class:'conversation-scope'},[
      h('h2','AI 对话'),h('p','模型服务：DeepSeek / deepseek-flash'),
      h('button',{'aria-label':'对话菜单','aria-expanded':menu.value,onClick:()=>menu.value=!menu.value},'⋯'),
      menu.value?h('div',[
        h('button',{disabled:active()||busy.value,onClick:()=>resetContext(false)},'不携带此前聊天，继续对话'),
        h('button',{disabled:active()||busy.value,onClick:()=>resetContext(true)},'清空记录并开始新对话'),
      ]):null,
      h('button',{ref:referenceButton,onClick:openReferences,'aria-haspopup':'dialog'},'参考资料'),
      h('small',`${reference.value?'参考简历':'不参考简历'} · 参考材料：${scope.value?.materials.length??0} 份 · 背景：0 条`),
      h('p','发送时将使用上述资料范围，并调用所示模型服务。'),
      !configured.value?h('p','DeepSeek 密钥尚未配置，暂不能发送。'):null,
      h('small','按容量携带近期完整交流，较早聊天可能不参与本次回答；不会自动摘要。'),
      h('small','停止参考只影响后续请求；旧回答保留当时内容，不会自动撤回或重答。'),
      ]),
      h('div',{class:'messages',tabindex:0,'aria-label':'AI 消息记录'},[
        hasMore.value?h('button',{onClick:loadOlder},'加载更早消息'):null,
        ...[...tasks.value].reverse().map((task,index,ordered)=>h('div',{key:task.id},[
        state.value.epoch>0&&!beforeBoundary(task)&&(index===0||beforeBoundary(ordered[index-1]))?h('p',{class:'context-boundary'},'后续对话不再携带以上聊天内容'):null,
        h('article',[
        h('strong','你的问题'),h('p',task.message),
        task.basis_changed?h('p','此回答基于变更前的简历或材料，请核对；未自动重新调用。'):null,
        task.answer?h('div',[h('strong','AI 回答'),h('p',{style:{whiteSpace:'pre-wrap'}},task.answer),h('button',{onClick:()=>copy(task.answer!)},'复制回答')]):null,
        ['ACCEPTED','CALLING'].includes(task.execution_status)?h('p',task.execution_status==='ACCEPTED'?'请求已受理，正在等待执行…':'正在回答…'):null,
        task.record_status==='SAVE_FAILED'?h('div',[h('p','回答未保存，重开可能丢失。解决前暂停发送。'),h('button',{onClick:()=>persist(task)},'重试保存回答')]):null,
        task.execution_status==='MODEL_FAILED'?h('p',`模型调用失败（${task.error_code}）。未自动重试。`):null,
        task.execution_status==='UNKNOWN'?h('div',[h('p','调用结果不明，不会自动重调；重新调用可能重复收费。'),
          task.error_code!=='UNKNOWN_ACKNOWLEDGED'?h('button',{onClick:async()=>{if(window.confirm('供应商可能已经执行原调用。确认承担重复费用风险并允许下一次主动发送？'))try{await request(base()+'/requests/'+task.id+'/acknowledge-unknown','POST',{},crypto.randomUUID());notice.value='已记录风险确认；请主动输入并发送，不会自动调用。';}catch(e){notice.value=failure(e);}}},'确认风险，允许下一次发送'):null]):null,
      ])])),
        state.value.epoch>0&&(!tasks.value.length||tasks.value.every(beforeBoundary))?h('p',{class:'context-boundary'},'后续对话不再携带以上聊天内容'):null,
      ]),
      h('p',{role:'status'},notice.value),
      reviewRequired.value?h('button',{onClick:()=>{reviewRequired.value=false;notice.value='已核对对话变化，请主动发送。';}},'已核对最新对话'):null,
      h('form',{onSubmit:(e:Event)=>{e.preventDefault();void send();}},[
        h('label',['向 AI 提问',h('textarea',{value:text.value,onInput:(e:Event)=>editDraft((e.target as HTMLTextAreaElement).value),
          onKeydown:(e:KeyboardEvent)=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();void send();}}})]),
        h('small','Enter 发送，Shift+Enter 换行。每区独立保存输入草稿。'),
        h('small',{role:'status'},draftStatus.value),
        draftStatus.value.includes('丢失')?h('button',{type:'button',onClick:()=>saveDraft()},'重试保存草稿'):null,
        draftConflict.value?h('button',{type:'button',onClick:async()=>{try{const latest=await request<{text:string;version:number}>(base()+'/draft');notice.value='别处保存的草稿：'+latest.text+'。本页输入仍保留。';if(window.confirm('已展示别处草稿，确认以本页文字作为下一次保存内容？')){draftVersion.value=latest.version;state.value=await request<typeof state.value>(base()+'/conversation-state');draftConflict.value=false;draftStatus.value='已核对，可主动重试保存草稿。';}}catch(e){notice.value=failure(e);}}},'核对别处草稿'):null,
        h('button',{type:'submit',disabled:busy.value||active()||reviewRequired.value||!configured.value||!text.value.trim()},busy.value?'正在受理…':active()?'正在回答或等待保存':'发送'),
      ]),
      h('dialog',{ref:referenceDialog,class:'reference-dialog','aria-labelledby':'reference-dialog-title',
        onCancel:(event:Event)=>{event.preventDefault();closeReferences();}},[
        h('h2',{id:'reference-dialog-title'},'参考资料'),
        h('p','选择即时生效，只影响后续请求，不会调用 AI。这里显示允许范围，不表示已经读取。'),
        h('fieldset',[h('legend','工作区简历'),h('label',[
          h('input',{type:'checkbox',checked:reference.value,disabled:referenceSaving.value,onChange:(e:Event)=>reference.value=(e.target as HTMLInputElement).checked}),'参考工作区简历']),
          scope.value&&!scope.value.resume_available?h('p','当前尚未建立简历，发送前请先建立或取消参考。'):null]),
        h('fieldset',[h('legend','个人材料'),
          referenceLoading.value?h('p','正在读取材料范围…'):!available.value.length?h('p','暂无个人材料，可从个人材料入口添加。'):null,
          ...available.value.map(material=>h('label',{class:'reference-material'},[
            h('input',{type:'checkbox',checked:scope.value?.materials.some(m=>m.id===material.id)??false,disabled:referenceSaving.value||referenceLoading.value,
              onChange:(e:Event)=>{void selectMaterial(material.id,(e.target as HTMLInputElement).checked);}}),material.name,
            material.limitation?h('small',material.limitation):null])),
          h('button',{disabled:referenceSaving.value,onClick:()=>{closeReferences();emit('manage');}},'打开个人材料管理')]),
        h('fieldset',[h('legend','已确认个人背景'),h('p','共 0 条；背景整理尚未开放。开放后同人物全部有效背景参与，不逐条选择。')]),
        h('p','停止参考不删除旧回答，也不撤回已发送的数据。旧聊天可能含相关内容，需要排除时可从对话菜单重置上下文。'),
        h('p',{role:'alert'},referenceError.value),
        h('button',{disabled:referenceSaving.value||referenceLoading.value,onClick:openReferences},'刷新参考资料'),
        h('button',{disabled:referenceSaving.value,onClick:closeReferences},referenceSaving.value?'正在保存…':'完成'),
      ]),
    ]);
  },
});