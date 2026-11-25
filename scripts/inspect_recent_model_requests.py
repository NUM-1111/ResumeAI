"""Only safe metadata for recent official-model requests; never prints chat text."""
import subprocess

sql = """SELECT json_build_object('id',id,'execution',execution_status,'record',record_status,
'error',error_code,'created_at',created_at,'elapsed_seconds',extract(epoch from updated_at-created_at),
'message_chars',length(message),'answer_chars',length(answer),'supplier_id_present',nullif(supplier_request_id,'') is not null,
'usage',nullif(usage_json,'')::json,'diagnostics',diagnostics_json::json)
FROM m00_app.ai_request WHERE model='deepseek-flash' ORDER BY created_at DESC LIMIT 6;"""
result = subprocess.run(['docker','exec','-i','agentcv-m00-postgres','psql','-X','-t','-A','-v','ON_ERROR_STOP=1',
                         '-U','agentcv_m00','-d','agentcv_m00'],input=sql,text=True,capture_output=True)
if result.returncode:
    raise SystemExit('Metadata query failed; details suppressed')
print(result.stdout.strip())