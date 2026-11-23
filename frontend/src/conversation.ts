import { defineComponent,h,ref,onMounted,onBeforeUnmount,watch,PropType } from 'vue';
import { request,ApiError } from './api';
type Scope={resume_version:number;resume_available:boolean;scope_version:number;materials:{id:string;name:string;text_version:number;limitation:string}[]};
type Task={id:string;message:string;answer:string|null;execution_status:string;record_status:string;error_code:string|null;model:string;basis_changed?:boolean};
export const conversation=defineComponent({
  props:{profileId:{type:String,required:true},workspaceId:{type:String,required:true},prepare:{type:Function as PropType<()=>Promise<boolean>>,required:true},scopeRevision:Number},
  emits:['auth','manage'],
  setup(props,{emit}){
    const scope=ref<Scope|null>(null),configured=ref(false),text=ref(''),reference=ref(true),tasks=ref<Task[]>([]),notice=ref(''),busy=ref(false);
    const referenceDialog=ref<HTMLDialogElement|null>(null),referenceButton=ref<HTMLButtonElement|null>(null);
    const available=ref<{id:string;name:string;limitation:string}[]>([]),referenceLoading=ref(false),referenceSaving=ref(false),referenceError=ref('');
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
        const result=await request<{requests:Task[]}>(base()+'/conversation');
        if(!alive)return;
        const values=result.requests;
        for(let i=0;i<values.length;i++)if(['ACCEPTED','CALLING'].includes(values[i].execution_status)||values[i].record_status==='SAVE_FAILED')
          values[i]=await request<Task>(base()+'/requests/'+values[i].id);
        if(alive)tasks.value=values;
      }catch(e){if(alive)notice.value=failure(e);}
      finally{if(alive)timer=setTimeout(poll,1500);}
    }
    async function send(){
      if(busy.value||active()||!text.value.trim()||!scope.value)return;
      busy.value=true;notice.value='';
      try{
        if(reference.value&&!await props.prepare()){notice.value='简历未保存，本条消息未发送。';return;}
        // Preserve scope/version conditions unless user explicitly refreshes; resume save is intentional.
        if(reference.value){const latest=await request<{version:number}>(base()+'/resume');scope.value.resume_version=latest.version;}
        const body={message:text.value,reference_resume:reference.value,expected_resume_version:scope.value.resume_version,
          expected_scope_version:scope.value.scope_version,material_versions:Object.fromEntries(scope.value.materials.map(m=>[m.id,m.text_version]))};
        const signature=JSON.stringify(body);
        if(!operation||operation.body!==signature)operation={body:signature,key:crypto.randomUUID()};
        const result=await request<Task>(base()+'/conversation','POST',body,operation.key);
        tasks.value=[result,...tasks.value.filter(t=>t.id!==result.id)];text.value='';operation=null;
      }catch(e){notice.value=failure(e);}finally{busy.value=false;}
    }
    async function persist(task:Task){try{const updated=await request<Task>(base()+'/requests/'+task.id+'/persist','POST',{},crypto.randomUUID());tasks.value=tasks.value.map(t=>t.id===task.id?updated:t);}catch(e){notice.value=failure(e);}}
    async function copy(answer:string){try{await navigator.clipboard.writeText(answer);notice.value='已复制回答。';}catch{notice.value='自动复制不可用，请选中回答文字手动复制。';}}
    const unload=(event:BeforeUnloadEvent)=>{if(text.value||tasks.value.some(t=>t.record_status==='SAVE_FAILED')){event.preventDefault();event.returnValue='';}};
    onMounted(async()=>{window.addEventListener('beforeunload',unload);try{const model=await request<{configured:boolean}>('/api/v1/model');configured.value=model.configured;await refreshScope();}catch(e){notice.value=failure(e);}void poll();});
    watch(()=>props.scopeRevision,()=>{void refreshScope().catch(e=>notice.value=failure(e));});
    onBeforeUnmount(()=>{alive=false;clearTimeout(timer);window.removeEventListener('beforeunload',unload);});
    return()=>h('section',{class:'conversation'},[
      h('div',{class:'conversation-scope'},[
      h('h2','AI 对话'),h('p','模型服务：DeepSeek / deepseek-flash'),
      h('button',{ref:referenceButton,onClick:openReferences,'aria-haspopup':'dialog'},'参考资料'),
      h('small',`${reference.value?'参考简历':'不参考简历'} · 参考材料：${scope.value?.materials.length??0} 份 · 背景：0 条`),
      h('p','发送时将使用上述资料范围，并调用所示模型服务。'),
      !configured.value?h('p','DeepSeek 密钥尚未配置，暂不能发送。'):null,
      h('p','当前仅验证单次问答：旧聊天暂不随新问题发送，持续交流与草稿持久化在后续开放。'),
      h('small','停止参考只影响后续请求；旧回答保留当时内容，不会自动撤回或重答。'),
      ]),
      h('div',{class:'messages',tabindex:0,'aria-label':'AI 消息记录'},[...tasks.value].reverse().map(task=>h('article',{key:task.id},[
        h('strong','你的问题'),h('p',task.message),
        task.basis_changed?h('p','此回答基于变更前的简历或材料，请核对；未自动重新调用。'):null,
        task.answer?h('div',[h('strong','AI 回答'),h('p',{style:{whiteSpace:'pre-wrap'}},task.answer),h('button',{onClick:()=>copy(task.answer!)},'复制回答')]):null,
        ['ACCEPTED','CALLING'].includes(task.execution_status)?h('p',task.execution_status==='ACCEPTED'?'请求已受理，正在等待执行…':'正在回答…'):null,
        task.record_status==='SAVE_FAILED'?h('div',[h('p','回答未保存，重开可能丢失。解决前暂停发送。'),h('button',{onClick:()=>persist(task)},'重试保存回答')]):null,
        task.execution_status==='MODEL_FAILED'?h('p',`模型调用失败（${task.error_code}）。未自动重试。`):null,
        task.execution_status==='UNKNOWN'?h('div',[h('p','调用结果不明，不会自动重调；重新调用可能重复收费。'),
          task.error_code!=='UNKNOWN_ACKNOWLEDGED'?h('button',{onClick:async()=>{if(window.confirm('供应商可能已经执行原调用。确认承担重复费用风险并允许下一次主动发送？'))try{await request(base()+'/requests/'+task.id+'/acknowledge-unknown','POST',{},crypto.randomUUID());notice.value='已记录风险确认；请主动输入并发送，不会自动调用。';}catch(e){notice.value=failure(e);}}},'确认风险，允许下一次发送'):null]):null,
      ]))),
      h('p',{role:'status'},notice.value),
      h('form',{onSubmit:(e:Event)=>{e.preventDefault();void send();}},[
        h('label',['向 AI 提问',h('textarea',{value:text.value,onInput:(e:Event)=>text.value=(e.target as HTMLTextAreaElement).value,
          onKeydown:(e:KeyboardEvent)=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();void send();}}})]),
        h('small','Enter 发送，Shift+Enter 换行。草稿暂不持久保存。'),
        h('button',{type:'submit',disabled:busy.value||active()||!configured.value||!text.value.trim()},busy.value?'正在受理…':active()?'正在回答或等待保存':'发送'),
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
        h('p','停止参考不删除旧回答，也不撤回已发送的数据。当前旧聊天不随新问题发送；上下文重置将在持续对话功能中提供。'),
        h('p',{role:'alert'},referenceError.value),
        h('button',{disabled:referenceSaving.value||referenceLoading.value,onClick:openReferences},'刷新参考资料'),
        h('button',{disabled:referenceSaving.value,onClick:closeReferences},referenceSaving.value?'正在保存…':'完成'),
      ]),
    ]);
  },
});