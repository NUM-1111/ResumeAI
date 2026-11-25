"""Offline checks for the explicit paid probe; never calls execute()."""
import io
import json
import unittest
from test_p2_stream_live import consume, payloads


def frame(delta, finish=None):
    return 'data: ' + json.dumps({'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}) + '\n\n'


class ProbeTest(unittest.TestCase):
    def test_chat(self):
        metadata = {}
        consume(io.BytesIO((frame({'content': '合成'}, 'stop') + 'data: [DONE]\n\n').encode()), metadata, 'chat', __import__('time').monotonic())
        self.assertEqual(metadata['content_characters'], 2)

    def test_two_tools_and_usage(self):
        calls = []
        for index, (name, args) in enumerate([
            ('propose_resume_change', {'title': '合成', 'scope': 'FULL', 'proposed_text': '协助部署'}),
            ('propose_background_entries', {'items': ['协助部署']})]):
            calls.append({'index': index, 'id': str(index), 'function': {'name': name, 'arguments': json.dumps(args)}})
        wire = frame({'tool_calls': calls}, 'tool_calls') + 'data: {"choices":[],"usage":{"total_tokens":42}}\n\ndata: [DONE]\n\n'
        metadata = {}
        consume(io.BytesIO(wire.encode()), metadata, 'act', __import__('time').monotonic())
        self.assertTrue(metadata['tool_schema_valid'])
        self.assertNotIn('协助部署', json.dumps(metadata, ensure_ascii=False))

    def test_missing_done_rejected(self):
        with self.assertRaises(ValueError):
            consume(io.BytesIO(frame({'content': 'partial'}, 'stop').encode()), {}, 'chat', __import__('time').monotonic())

    def test_budget(self):
        self.assertEqual(sum(body['max_tokens'] for _, body in payloads()), 896)


if __name__ == '__main__':
    unittest.main()