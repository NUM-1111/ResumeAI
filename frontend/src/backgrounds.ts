import {defineComponent,h,ref,onMounted} from 'vue';
import {request,ApiError} from './api';
import {BackgroundChange,backgroundEditor,changeBody,changePreview} from './backgroundChange';
export const backgrounds=defineComponent({
  props:{profileId:{type:String,required:true},workspaceId:{type:String,required:true}},emits:['close','auth','dirty','busy'],
  setup(props,{emit}){
    const items=ref<{id:string;text:string;version:number}[]>([]),status=ref('正在读取…');
    const editing=ref<string|null>(null),text=ref(''),proposal=ref<BackgroundChange|null>(null),busy=ref(false);
    const recovered=ref<BackgroundChange|null>(null);
    let pendingKey:string|null=null;
    const base=()=>`/api/v1/profiles/${props.profileId}`;
    function error(e:unknown){if(e instanceof ApiError&&(e.status===401||e.status===403))emit('auth');status.value=e instanceof ApiError?e.message:'保存结果未确认，请保留输入后重试。';}
    async function prepare(item:typeof items.value[number],kind:'CORRECT'|'WITHDRAW'){
      if(busy.value)return;busy.value=true;emit('busy',true);
      try{proposal.value=await request<BackgroundChange>(base()+'/backgrounds/'+item.id+'/changes','POST',{workspace_id:props.workspaceId,expected_version:item.version,old_text:item.text,new_text:kind==='CORRECT'?text.value:null,kind},crypto.randomUUID());pendingKey=crypto.randomUUID();status.value='';emit('dirty',true);}
      catch(e){error(e);void restorePending();}finally{busy.value=false;emit('busy',false);}
    }
    async function apply(){if(!proposal.value||busy.value)return;busy.value=true;emit('busy',true);
      try{await request(base()+'/background-changes/'+proposal.value.id+'/confirm','POST',changeBody(proposal.value),pendingKey!);proposal.value=null;editing.value=null;emit('dirty',false);await load();status.value='背景变更已生效。';}
      catch(e){error(e);}finally{busy.value=false;emit('busy',false);}
    }
    async function cancel(){if(busy.value)return;if(proposal.value){try{await request(base()+'/background-changes/'+proposal.value.id+'/dismiss','POST',{workspace_id:props.workspaceId},crypto.randomUUID());}catch(e){error(e);return;}}proposal.value=null;recovered.value=null;editing.value=null;emit('dirty',false);}
    async function restorePending(){try{const result=await request<Partial<BackgroundChange>>(base()+'/workspaces/'+props.workspaceId+'/background-changes');recovered.value=result.id?result as BackgroundChange:null;}catch(e){error(e);}}
    async function load(){try{const result=await request<{items:typeof items.value}>(`/api/v1/profiles/${props.profileId}/backgrounds`);items.value=result.items;status.value='';await restorePending();}
      catch(e){if(e instanceof ApiError&&(e.status===401||e.status===403))emit('auth');status.value=e instanceof ApiError?e.message:'背景暂时无法读取，请重试。';}}
    onMounted(load);
    return()=>h('section',[h('h1','已确认个人背景'),h('p','当前人物全部有效背景供各工作区 AI 对话使用，不修改简历或材料。'),
      h('p','可直接点击每条记录下方的“纠正”自行修改，或点击“撤回”。这些操作不需要 AI，也不会调用模型；提交后还需核对内容并确认。'),
      h('button',{disabled:busy.value,onClick:()=>emit('close')},'返回工作区简历'),h('button',{disabled:busy.value,onClick:load},'刷新背景'),h('p',{role:'status'},status.value),
      recovered.value&&!proposal.value?h('div',[h('p','此工作区有尚未确认的背景变更。可继续核对或取消，无需清空聊天。'),h('button',{disabled:busy.value,onClick:()=>{proposal.value=recovered.value;pendingKey=crypto.randomUUID();status.value='';emit('dirty',true);}},'继续处理待确认背景变更')]):null,
      !items.value.length&&!status.value?h('p','尚无已确认背景。可继续普通对话，明确确认候选后才保存。'):null,
      h('ol',items.value.map(item=>h('li',{key:item.id,style:{whiteSpace:'pre-wrap'}},[
        h('p',item.text),h('button',{disabled:busy.value||!!editing.value||!!proposal.value,onClick:()=>{editing.value=item.id;text.value=item.text;status.value='';emit('dirty',true);}},'纠正'),
        h('button',{disabled:busy.value||!!editing.value||!!proposal.value,onClick:()=>prepare(item,'WITHDRAW')},'撤回'),
        editing.value===item.id&&!proposal.value?h(backgroundEditor,{oldText:item.text,text:text.value,busy:busy.value,status:status.value,onEdit:(value:string)=>text.value=value,onCancel:cancel,onSubmit:()=>prepare(item,'CORRECT')}):null,
       ]))),proposal.value?changePreview(proposal.value,apply,cancel,busy.value,status.value):null]);
  }
});