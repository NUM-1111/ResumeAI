package com.agentcv.background;

import com.agentcv.api.ApiFailure;
import com.agentcv.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("m00")
public class BackgroundService {
    private final JdbcTemplate db;private final WorkspaceService workspaces;private final ObjectMapper json;
    public BackgroundService(JdbcTemplate db,WorkspaceService workspaces,ObjectMapper json){this.db=db;this.workspaces=workspaces;this.json=json;}
    public static final String PURPOSE="确认后供该人物各工作区的 AI 对话使用，不修改简历或材料。";
    public static final String PROTOCOL="\n背景候选协议：只输出 JSON 对象 {\"answer\":\"简短普通回答\",\"background_candidates\":[\"确切候选1\",\"确切候选2\"]}，无 markdown 围栏。无合适候选时数组为空。每次最多8项，每项最多1000字。候选只包含用户明确描述或请求整理的具体经历，不推断成果、数字或扩大职责。假设、拒绝、暂缓或含糊表达不形成确认。用户修正候选时先重新展示修正后的确切候选，不继承原确认。候选由系统统一展示，answer中不要重复候选、用途、确认指令或系统规则。当前有效背景已成功保存，不要重复请求确认或再次生成相同候选。你不能自行宣称新增背景已保存；实际有效背景以系统白名单为准。";
    public static final String CHANGE_PROTOCOL="\n背景纠正/撤回：若用户明确要求改动一条现有背景，输出 background_action 对象 {\"id\":\"系统给出的背景UUID\",\"version\":1,\"old_text\":\"原文逐字匹配\",\"kind\":\"CORRECT或WITHDRAW\",\"new_text\":\"纠正后确切内容，撤回为null\"}，并令background_candidates为空；其余情况background_action为null。此对象仅提议，系统会展示前后内容并等待用户独立确认，你不能宣称已经执行。多条可能时先澄清，不猜目标。新描述与背景冲突时展示旧新差异，问是不同事件、纠正记录、仅讨论措辞还是暂缓；不同事件补事件范围后另行确认新增，措辞讨论不写背景，暂缓保留分歧但不反复追问、不阻断无关问题。历史撤回或纠正说明优先于旧聊天，旧记录不得继续当作当前事实，也不能重新作为新增候选恢复。";
    public record Action(UUID id,long version,String old_text,String new_text,String kind) {}
    public record Output(String answer,List<String> items,Action action) {
        public Output(String answer,List<String> items){this(answer,items,null);}
    }
    public Output decode(String raw){
        try{
            String value=raw.strip();if(value.startsWith("```json")&&value.endsWith("```"))value=value.substring(7,value.length()-3).strip();
            var node=json.readTree(value);
            if(!node.isObject()||!node.path("answer").isTextual()||node.path("answer").asText().isBlank())return new Output(raw,List.of());
            List<String> items=new ArrayList<>();var list=node.path("background_candidates");
            if(list.isArray()&&list.size()<=8)for(var item:list){
                if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>1000)return new Output(node.path("answer").asText(),List.of());
                items.add(item.asText().strip());
            }
            Action action=null;var proposal=node.path("background_action");
            if(proposal.isObject())try{action=new Action(UUID.fromString(proposal.path("id").asText()),proposal.path("version").asLong(),proposal.path("old_text").asText(),proposal.path("new_text").isNull()?null:proposal.path("new_text").asText(),proposal.path("kind").asText());}catch(IllegalArgumentException ignored){}
            return new Output(node.path("answer").asText(),action==null?items.stream().distinct().toList():List.of(),action);
        }catch(Exception ignored){return new Output(raw,List.of());}
    }
    public String display(Output output){
        if(output.action!=null)return "背景变更提议尚未生效，请在下方核对确切记录后确认。";
        if(output.items.isEmpty())return output.answer;
        StringBuilder answer=new StringBuilder("待保存的个人背景：\n");
        for(int i=0;i<output.items.size();i++)answer.append(i+1).append(". ").append(output.items.get(i)).append('\n');
        return answer.append("保存后供此人物各工作区使用。确认保存全部，或说明只保存哪项？").toString();
    }
    public void stage(UUID p,UUID w,UUID request,Output output){
        // Called in the same short transaction that saves the displayed assistant answer.
        db.update("update background_candidate set status='EXPIRED' where workspace_id=? and status='PENDING'",w);
        if(output.items.isEmpty())return;
        Long epoch=db.queryForObject("select epoch from conversation_state where workspace_id=?",Long.class,w);
        db.update("insert into background_candidate(id,profile_id,workspace_id,request_id,context_epoch,items_json,display_text) values (?,?,?,?,?,?,?)",UUID.randomUUID(),p,w,request,epoch,encode(output.items),display(output));
    }
    public Map<String,Object> list(UUID p){workspaces.profile(p);return Map.of("items",rows(p));}
    public List<Map<String,Object>> rows(UUID p){return db.queryForList("select id,text,version,created_at from background where profile_id=? and active order by created_at,id",p);}
    public Map<String,Object> pending(UUID p,UUID w){
        workspaces.workspace(p,w);
        var rows=db.queryForList("select c.id,c.version,c.request_id,c.display_text,c.items_json from background_candidate c join conversation_state s on s.workspace_id=c.workspace_id where c.profile_id=? and c.workspace_id=? and c.status='PENDING' and c.context_epoch=s.epoch order by c.created_at desc limit 1",p,w);
        if(rows.isEmpty())return Map.of();var row=rows.get(0);row.put("items",items((String)row.remove("items_json")));return row;
    }
    public record Confirm(UUID workspace_id,Long expected_version,String displayed_text,String user_text) {}
    public Map<String,Object> confirm(UUID p,UUID c,UUID key,Confirm input){
        if(input.workspace_id==null||input.expected_version==null||input.displayed_text==null||input.user_text==null)throw invalid();
        return workspaces.write("BACKGROUND_CONFIRM/"+p+"/"+c,key,input,()->workspaces.workspace(p,input.workspace_id),()->{
            db.queryForObject("select id from workspace where id=? and profile_id=? for update",UUID.class,input.workspace_id,p);
            db.queryForObject("select id from profile where id=? for update",UUID.class,p);
            var candidates=db.queryForList("select * from background_candidate where id=? and profile_id=? and workspace_id=? for update",c,p,input.workspace_id);
            if(candidates.isEmpty())throw new ApiFailure(404,"NOT_FOUND","候选不可用。");var candidate=candidates.get(0);
            if(input.expected_version.longValue()!=((Number)candidate.get("version")).longValue()||!input.displayed_text.equals(candidate.get("display_text")))throw stale();
            List<String> items=items((String)candidate.get("items_json"));List<Integer> selected=selection(input.user_text,items.size());
            var previous=db.queryForList("select id,selected_json from background_confirmation where candidate_id=?",c);
            if(!previous.isEmpty()){
                if(!encode(selected).equals(previous.get(0).get("selected_json")))throw stale();
                return saved((UUID)previous.get(0).get("id"),p);
            }
            var pending=pending(p,input.workspace_id);
            if(!"PENDING".equals(candidate.get("status"))||!c.equals(pending.get("id")))throw stale();
            if(db.queryForObject("select count(*) from ai_request where workspace_id=? and execution_status in ('ACCEPTED','CALLING')",Integer.class,input.workspace_id)>0)throw stale();
            UUID confirmation=UUID.randomUUID();
            db.update("insert into background_confirmation(id,profile_id,candidate_id,candidate_version,user_text,selected_json) values (?,?,?,?,?,?)",confirmation,p,c,input.expected_version,input.user_text,encode(selected));
            for(int number:selected)db.update("insert into background(id,profile_id,confirmation_id,item_number,text) values (?,?,?,?,?)",UUID.randomUUID(),p,confirmation,number,items.get(number-1));
            db.update("update background_candidate set status='CONFIRMED' where id=?",c);
            return saved(confirmation,p);
        });
    }
    private Map<String,Object> saved(UUID confirmation,UUID p){return Map.of("saved",true,"items",db.queryForList("select id,text from background where confirmation_id=? and profile_id=? order by item_number",confirmation,p));}
    public List<Integer> selection(String text,int count){
        String value=text.replaceAll("\\s+","").replaceAll("[。！!]$","");
        value=value.replace('一','1').replace('二','2').replace('三','3').replace('四','4').replace('五','5').replace('六','6').replace('七','7').replace('八','8');
        if(value.matches("(?:确认|确认保存|确认并保存|确认保存全部|确认全部保存|确认全部|确认全部并保存|保存全部|全部保存|都保存|全部确认)"))return java.util.stream.IntStream.rangeClosed(1,count).boxed().toList();
        var natural=Pattern.compile("(?:确认)?(?:仅|只)(?:保留|保存|确认)第?([1-8](?:[、,，][1-8])*)项(?:作为背景|为背景|并保存)?").matcher(value);
        if(natural.matches())value="只确认第"+natural.group(1)+"项并保存";
        if(value.matches("(?:确认以上全部并保存|以上(?:全部|完全)?准确[，,]同意保存|全部确认[，,]?(?:同意)?保存)"))return java.util.stream.IntStream.rangeClosed(1,count).boxed().toList();
        var match=Pattern.compile("只?确认第([1-8](?:[、,，][1-8])*)项[，,]?(?:并|同意)?保存").matcher(value);
        if(!match.matches())throw invalid();
        List<Integer> numbers=Arrays.stream(match.group(1).split("[、,，]")).map(Integer::valueOf).distinct().sorted().toList();
        if(numbers.stream().anyMatch(n->n>count))throw invalid();return numbers;
    }
    private ApiFailure invalid(){return new ApiFailure(400,"CONFIRMATION_UNCLEAR","尚未保存。请明确确认展示内容，例如“确认以上全部并保存”或“只确认第 1 项并保存”；修正请继续对话。");}
    private ApiFailure stale(){return new ApiFailure(409,"CANDIDATE_CHANGED","候选或对话已变化，未写入旧确认，请核对最新展示内容。");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode background state");}}
    private List<String> items(String value){try{return json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){throw new IllegalStateException("Cannot decode background state");}}
}