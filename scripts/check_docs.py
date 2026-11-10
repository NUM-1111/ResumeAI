"""Validate Markdown local links and references to the project docs tree."""
import re
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
errors = []
historical_missing = []
for folder in [ROOT, ROOT / 'docs', ROOT / '核心交接', ROOT / 'scripts']:
    paths = folder.glob('*.md') if folder == ROOT else folder.rglob('*')
    for path in paths:
        if not path.is_file() or path.suffix not in ('.md', '.py') or path.name == 'check_docs.py':
            continue
        text = path.read_text()
        if path.suffix == '.md':
            for link in re.findall(r'\]\(([^)]+)\)', text):
                link = unquote(link.split('#')[0])
                if not link or '://' in link or link.startswith('mailto:'):
                    continue
                target = Path(link) if link.startswith('/') else path.parent / link
                if not target.exists():
                    entry=str(path.relative_to(ROOT)) + ': ' + link
                    if path.parent == ROOT / '核心交接' and link.startswith('../'):
                        historical_missing.append(entry)
                    else:
                        errors.append(entry)
        for ref in re.findall(r'docs/([^`\s)"\'<>]+\.(?:md|txt|json))', text):
            if not (ROOT / 'docs' / ref).exists():
                errors.append(str(path.relative_to(ROOT)) + ': docs/' + ref)
if errors:
    raise SystemExit('\n'.join(sorted(set(errors))))
print('PASS: local Markdown links and docs file references exist')
for entry in historical_missing:
    print('HISTORICAL EXTERNAL SOURCE NOT INCLUDED: ' + entry)