"""Explicit, at most two paid synthetic calls. Never imported by verify/smoke.

Stores only metadata; exclusive marker prevents accidental replay after failure.
Uses Python HTTPS HTTP/1.1, not the production Java gateway.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import time

from model_environment import load_model_environment

ROOT = Path(__file__).resolve().parents[1]
RESULT = ROOT / '.local/p2-stream-live-result.json'
MODEL = 'deepseek-flash'


def payloads():
    common = {'model': MODEL, 'stream': True, 'thinking': {'type': 'disabled'},
              'stream_options': {'include_usage': True}}
    chat = dict(common, max_tokens=128, messages=[{'role': 'user', 'content':
        '这只是合成软件测试，不是真实经历。请用两句中文说明简历应如实表达职责，不要输出JSON。'}])
    def tool(name, properties, required):
        return {'type': 'function', 'function': {'name': name,
            'description': 'Create a proposal only. Never execute or save personal facts.',
            'parameters': {'type': 'object', 'properties': properties,
                           'required': required, 'additionalProperties': False}}}
    act = dict(common, max_tokens=768, tool_choice='auto', tools=[
        tool('propose_resume_change', {'title': {'type': 'string'},
             'scope': {'type': 'string', 'enum': ['FULL']}, 'proposed_text': {'type': 'string'}},
             ['title', 'scope', 'proposed_text']),
        tool('propose_background_entries', {'items': {'type': 'array', 'items': {'type': 'string'},
             'minItems': 1, 'maxItems': 2}}, ['items'])], messages=[
        {'role': 'system', 'content': 'Only synthetic test data. Use both provided functions to propose changes. Do not claim anything was saved or applied. Keep arguments short and preserve facts.'},
        {'role': 'user', 'content': '合成人物，非真实经历：在合成订单项目中参与接口开发，仅协助测试环境部署。请将这份短简历精炼表达，同时提炼这两条背景候选。请调用两个工具，各一次，不要执行保存，不编造数字或独立负责。'}])
    return [('chat', chat), ('act', act)]


def validate_tools(tools):
    expected = {'propose_resume_change', 'propose_background_entries'}
    if {tool['name'] for tool in tools.values()} != expected or len(tools) != 2:
        raise ValueError('EXPECTED_TWO_TOOLS')
    for tool in tools.values():
        if not tool['id']:
            raise ValueError('MISSING_TOOL_ID')
        args = json.loads(tool['arguments'])
        if tool['name'] == 'propose_resume_change':
            if set(args) != {'title', 'scope', 'proposed_text'} or args['scope'] != 'FULL':
                raise ValueError('RESUME_SCHEMA')
            if not all(isinstance(args[field], str) and args[field].strip()
                       for field in ('title', 'proposed_text')):
                raise ValueError('RESUME_TEXT')
        else:
            if set(args) != {'items'} or not isinstance(args['items'], list) or not 1 <= len(args['items']) <= 2:
                raise ValueError('BACKGROUND_SCHEMA')
            if not all(isinstance(item, str) and item.strip() for item in args['items']):
                raise ValueError('BACKGROUND_TEXT')


def consume(response, metadata, kind, started):
    tools = {}; data = []; size = 0; text_chars = 0; done = False; finish = None
    metadata.update(content_chunks=0, tool_delta_chunks=0, frames=0)
    while True:
        if time.monotonic() - started > 60:
            raise TimeoutError('TOTAL_DEADLINE')
        line = response.readline(65538)
        size += len(line)
        if size > 2 * 1024 * 1024 or len(line) > 65536:
            raise ValueError('STREAM_LIMIT')
        if not line:
            break
        value = line.decode('utf-8', errors='strict').rstrip('\r\n')
        if value:
            if value.startswith('data:'):
                field = value[5:]
                data.append(field[1:] if field.startswith(' ') else field)
            continue
        if not data:
            continue
        body = '\n'.join(data); data.clear()
        metadata['frames'] += 1
        if body == '[DONE]':
            done = True
            break
        node = json.loads(body)
        if node.get('usage'):
            metadata['usage'] = {key: value for key, value in node['usage'].items()
                                 if key in ('prompt_tokens', 'completion_tokens', 'total_tokens',
                                            'prompt_cache_hit_tokens', 'prompt_cache_miss_tokens')
                                 and isinstance(value, (int, float))}
        for choice in node.get('choices', []):
            if choice.get('index') != 0 or finish is not None:
                raise ValueError('UNEXPECTED_CHOICE')
            delta = choice.get('delta', {})
            content = delta.get('content')
            if content:
                text_chars += len(content); metadata['content_chunks'] += 1
                metadata.setdefault('first_content_ms', round((time.monotonic() - started) * 1000))
            for item in delta.get('tool_calls', []):
                index = item['index']
                if not isinstance(index, int) or not 0 <= index < 4:
                    raise ValueError('TOOL_INDEX')
                tool = tools.setdefault(index, {'id': '', 'name': '', 'arguments': ''})
                if item.get('id'):
                    if tool['id'] and tool['id'] != item['id']:
                        raise ValueError('TOOL_ID_CHANGED')
                    tool['id'] = item['id']
                function = item.get('function', {})
                if function.get('name'):
                    if tool['name'] and tool['name'] != function['name']:
                        raise ValueError('TOOL_NAME_CHANGED')
                    tool['name'] = function['name']
                tool['arguments'] += function.get('arguments', '')
                metadata['tool_delta_chunks'] += 1
                metadata.setdefault('first_tool_ms', round((time.monotonic() - started) * 1000))
            if choice.get('finish_reason'):
                finish = choice['finish_reason']
    metadata.update(bytes_received=size, done=done, finish_reason=finish,
                    content_characters=text_chars,
                    tool_names=sorted(tool['name'] for tool in tools.values()))
    if not done:
        raise ValueError('MISSING_DONE')
    if kind == 'chat':
        if finish != 'stop' or not text_chars or tools:
            raise ValueError('CHAT_RESULT')
    else:
        if finish != 'tool_calls':
            raise ValueError('ACT_FINISH')
        validate_tools(tools)
        metadata['tool_schema_valid'] = True
        # No raw generated text or tool arguments are returned or persisted.


def execute():
    key = load_model_environment(os.environ, ROOT / '.env').get('DEEPSEEK_API_KEY', '').strip()
    if not key:
        raise SystemExit('MODEL_KEY_NOT_CONFIGURED')
    requests = payloads()
    if any(len(json.dumps(body).encode()) > 8192 for _, body in requests):
        raise SystemExit('SYNTHETIC_INPUT_LIMIT')
    RESULT.parent.mkdir(exist_ok=True)
    # Claim before dispatch: failure does not permit rerunning this file automatically.
    with RESULT.open('x', encoding='utf-8') as output:
        json.dump({'status': 'CLAIMED', 'attempts': []}, output)
    os.chmod(RESULT, 0o600)
    result = {'status': 'RUNNING', 'model': MODEL, 'client': 'python-http.client-HTTP/1.1',
              'synthetic_only': True, 'max_requests': 2, 'max_output_tokens_total': 896, 'attempts': []}
    def save():
        RESULT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    for kind, body in requests:
        meta = {'kind': kind, 'dispatch_marked': True, 'status': 'DISPATCHING'}
        result['attempts'].append(meta); save()
        started = time.monotonic(); connection = http.client.HTTPSConnection('api.deepseek.com', timeout=15)
        try:
            connection.request('POST', '/chat/completions', json.dumps(body).encode(),
                               {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
            # Socket inactivity bound; total checked at each line, at most one read overrun.
            response = connection.getresponse()
            meta.update(http_status=response.status, headers_ms=round((time.monotonic()-started)*1000),
                        protocol='HTTP/1.1' if response.version == 11 else 'OTHER')
            if response.status != 200:
                raise ValueError('SUPPLIER_HTTP_ERROR')
            if 'text/event-stream' not in response.getheader('Content-Type', ''):
                raise ValueError('NOT_SSE')
            consume(response, meta, kind, started)
            meta['status'] = 'PASS'
        except Exception as error:
            meta.update(status='FAIL_OR_UNKNOWN', failure_type=type(error).__name__)
            # Exception messages may contain network/response information: never persist them.
            result['status'] = 'STOPPED_NO_RETRY'
        finally:
            connection.close(); meta['elapsed_ms'] = round((time.monotonic()-started)*1000); save()
        if meta['status'] != 'PASS':
            break
    else:
        result['status'] = 'PASS'
    save(); print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-paid-synthetic', action='store_true')
    options = parser.parse_args()
    if not options.run_paid_synthetic:
        parser.error('Explicit --run-paid-synthetic required; never auto-retry')
    execute()