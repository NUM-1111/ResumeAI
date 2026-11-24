import {h,defineComponent,ref,onMounted,onBeforeUnmount,PropType} from 'vue';
export type BackgroundChange={id:string;workspace_id:string;expected_version:number;old_text:string;new_text:string|null;kind:'CORRECT'|'WITHDRAW';source_request_id?:string|null};
export const backgroundEditor=defineComponent({
  props:{oldText:{type:String,required:true},text:{type:String,required:true},busy:Boolean,status:String},emits:['edit','submit','cancel'],
  setup(props,{emit}){
    const dialog=ref<HTMLDialogElement|null>(null);let previous:HTMLElement|null=null;
    onMounted(()=>{previous=document.activeElement as HTMLElement;dialog.value?.showModal();});
    onBeforeUnmount(()=>{dialog.value?.close();queueMicrotask(()=>{if(previous?.isConnected)previous.focus();});});
    return()=>h('dialog',{ref:dialog,class:'reference-dialog','aria-label':'编辑个人背景',onCancel:(event:Event)=>{event.preventDefault();if(!props.busy)emit('cancel');}},[
      h('h3','纠正个人背景'),h('p',{style:{whiteSpace:'pre-wrap'}},'原记录：'+props.oldText),
      h('label',['纠正后的背景',h('textarea',{autofocus:true,value:props.text,disabled:props.busy,onInput:(event:Event)=>emit('edit',(event.target as HTMLTextAreaElement).value)})]),
      h('p','提交后将展示修改前后内容，最终确认后才生效。'),h('p',{role:'alert'},props.status),
      h('button',{disabled:props.busy,onClick:()=>emit('cancel')},'取消编辑背景'),
      h('button',{disabled:props.busy||!props.text.trim()||props.text===props.oldText,onClick:()=>emit('submit')},props.busy?'正在提交…':'提交纠正'),
    ]);
  }
});
export function changeBody(change:BackgroundChange){return {workspace_id:change.workspace_id,expected_version:change.expected_version,old_text:change.old_text,new_text:change.new_text,kind:change.kind};}
const changeDialog=defineComponent({
  props:{change:{type:Object as PropType<BackgroundChange>,required:true},busy:Boolean,status:String},emits:['confirm','cancel'],
  setup(props,{emit}){
    const dialog=ref<HTMLDialogElement|null>(null);let previous:HTMLElement|null=null;
    onMounted(()=>{previous=document.activeElement as HTMLElement;dialog.value?.showModal();});
    onBeforeUnmount(()=>{dialog.value?.close();if(previous?.isConnected&&!previous.hasAttribute('disabled'))previous.focus();else document.querySelector<HTMLElement>('main button:not(:disabled),nav button:not(:disabled)')?.focus();});
    return()=>{const change=props.change;return h('dialog',{ref:dialog,class:'reference-dialog','aria-label':'确认背景变更',onCancel:(event:Event)=>{event.preventDefault();if(!props.busy)emit('cancel');}},[
    h('h3',change.kind==='WITHDRAW'?'确认撤回背景':'确认纠正背景'),
    h('p',{style:{whiteSpace:'pre-wrap'}},'原记录：'+change.old_text),
    change.kind==='CORRECT'?h('p',{style:{whiteSpace:'pre-wrap'}},'拟修改为：'+change.new_text):null,
    h('p',change.kind==='WITHDRAW'?'后续不再作为此人物各工作区的有效背景，界面不能直接恢复。旧聊天、简历及材料不变，已外发内容不能撤回。':'确认后此人物各工作区使用新内容，不修改简历、材料或旧聊天。'),
    h('p',{role:'alert'},props.status),
    h('button',{autofocus:true,disabled:props.busy,onClick:()=>emit('cancel')},'取消背景变更'),
    h('button',{disabled:props.busy,onClick:()=>emit('confirm')},props.busy?'正在保存…':change.kind==='WITHDRAW'?'确认撤回':'确认纠正'),
  ]);};
  }
});
export function changePreview(change:BackgroundChange,confirm:()=>unknown,cancel:()=>unknown,busy:boolean,status=''){
  return h(changeDialog,{key:change.id,change,busy,status,onConfirm:confirm,onCancel:cancel});
}