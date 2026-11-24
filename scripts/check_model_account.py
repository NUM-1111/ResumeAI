"""Read-only official account diagnostics. Never prints credentials or chat text."""
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

from model_environment import load_model_environment

ROOT = Path(__file__).resolve().parents[1]


def check():
    file_key = load_model_environment({}, ROOT / '.env').get('DEEPSEEK_API_KEY', '')
    key = os.environ.get('DEEPSEEK_API_KEY', file_key)
    source = 'shell' if 'DEEPSEEK_API_KEY' in os.environ else '.env'
    processes = []
    for folder in Path('/proc').iterdir():
        if not folder.name.isdigit():
            continue
        try:
            args = (folder / 'cmdline').read_bytes().split(b'\0')
            if not args or args[0].rsplit(b'/', 1)[-1] != b'java':
                continue
            if str(ROOT / 'target/agentcv-0.0.1-SNAPSHOT.jar').encode() not in args:
                continue
            values = dict(entry.split(b'=', 1) for entry in (folder / 'environ').read_bytes().split(b'\0') if b'=' in entry)
            runtime_key = values.get(b'DEEPSEEK_API_KEY', b'').decode()
            worker_disabled = values.get(b'AGENTCV_DISABLE_AI_WORKER') == b'true'
            processes.append({'pid': int(folder.name), 'worker_disabled': worker_disabled,
                              'key_present': bool(runtime_key.strip()), 'key_matches_env_file': runtime_key == file_key})
            if not worker_disabled:
                key, source = runtime_key, 'running-application'
        except (OSError, ValueError, UnicodeError):
            continue
    print(json.dumps({'processes': processes, 'query_key_source': source, 'key_present': bool(key.strip())}))
    if key.strip():
        request = urllib.request.Request('https://api.deepseek.com/user/balance', headers={'Authorization': 'Bearer ' + key})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                data = json.load(response)
            print(json.dumps({'official_balance': {name: data.get(name) for name in ('is_available', 'balance_infos')}}))
        except urllib.error.HTTPError as error:
            print(json.dumps({'balance_http_status': error.code}))
        except Exception as error:
            print(json.dumps({'balance_query_error_type': type(error).__name__}))
    sql = """SELECT json_build_object('execution_status',execution_status,'error_code',error_code,
        'requests',count(*),'supplier_ids',count(nullif(supplier_request_id,'')),
        'total_tokens',sum((nullif(usage_json,'')::json->>'total_tokens')::bigint)::text)
        FROM ai_request GROUP BY execution_status,error_code ORDER BY execution_status,error_code;"""
    # Includes synthetic test data: these counts MUST NOT be treated as paid API usage.
    print('Local aggregates include test doubles and are not billing evidence:')
    result = subprocess.run(['docker', 'exec', 'agentcv-m00-postgres', 'psql', '-X', '-t', '-A',
                             '-U', 'agentcv_m00', '-d', 'agentcv_m00', '-c', 'SET search_path=m00_app; ' + sql],
                            capture_output=True, text=True)
    if result.returncode == 0:
        print(result.stdout.strip())
    else:
        print('Database aggregate query unavailable (details suppressed).')


if __name__ == '__main__':
    check()