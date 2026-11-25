"""Read-only metadata summary for the recorded final-experience window, not a bill."""
import subprocess

sql = """SELECT json_build_object('execution',execution_status,'requests',count(*),
'first_at',min(created_at),'last_at',max(created_at),
'requests_with_usage',count(*) FILTER(WHERE nullif(usage_json,'')::json->>'total_tokens' IS NOT NULL),
'input_tokens',sum((nullif(usage_json,'')::json->>'prompt_tokens')::bigint),
'output_tokens',sum((nullif(usage_json,'')::json->>'completion_tokens')::bigint),
'cache_hit_tokens',sum((nullif(usage_json,'')::json->>'prompt_cache_hit_tokens')::bigint),
'cache_miss_tokens',sum((nullif(usage_json,'')::json->>'prompt_cache_miss_tokens')::bigint),
'total_tokens',sum((nullif(usage_json,'')::json->>'total_tokens')::bigint))
FROM m00_app.ai_request
WHERE model='deepseek-flash' AND created_at >= '2026-11-24T14:50:00Z'::timestamptz
GROUP BY execution_status ORDER BY execution_status;"""
result = subprocess.run(['docker','exec','-i','agentcv-m00-postgres','psql','-X','-t','-A','-v','ON_ERROR_STOP=1',
                         '-U','agentcv_m00','-d','agentcv_m00'],input=sql,text=True,capture_output=True)
if result.returncode:
    raise SystemExit('Usage metadata query failed; details suppressed')
print('Window: 2026-11-24 22:50 Asia/Shanghai onward; official-model records only; not an invoice.')
print(result.stdout.strip())