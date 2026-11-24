import {defineComponent,h,ref,onMounted} from 'vue';
import {request,ApiError} from './api';
export const backgrounds=defineComponent({
  props:{profileId:{type:String,required:true}},emits:['close','auth'],
  setup(props,{emit}){
    const items=ref<{id:string;text:string}[]>([]),status=ref('正在读取…');
    async function load(){try{const result=await request<{items:typeof items.value}>(`/api/v1/profiles/${props.profileId}/backgrounds`);items.value=result.items;status.value='';}
      catch(e){if(e instanceof ApiError&&(e.status===401||e.status===403))emit('auth');status.value=e instanceof ApiError?e.message:'背景暂时无法读取，请重试。';}}
    onMounted(load);
    return()=>h('section',[h('h1','已确认个人背景'),h('p','当前人物全部有效背景供各工作区 AI 对话使用，不修改简历或材料。'),
      h('button',{onClick:()=>emit('close')},'返回工作区简历'),h('button',{onClick:load},'刷新背景'),h('p',{role:'status'},status.value),
      !items.value.length&&!status.value?h('p','尚无已确认背景。可继续普通对话，明确确认候选后才保存。'):null,
      h('ol',items.value.map(item=>h('li',{key:item.id,style:{whiteSpace:'pre-wrap'}},item.text)))]);
  }
});