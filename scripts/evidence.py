"""Inspect checkpoint files and redact actual local credentials from diagnostics."""
import hashlib
import json
from pathlib import Path
import subprocess
from local import ROOT, LOCAL, DATA, PG, config, pg

credentials = config()
redacted = []
for folder in (LOCAL, ROOT / 'target/surefire-reports'):
    if not folder.exists():
        continue
    for path in folder.rglob('*'):
        if path.is_file() and path.suffix in ('.log', '.xml', '.txt'):
            original = path.read_text(errors='replace')
            safe = original
            for secret in credentials.values():
                safe = safe.replace(secret, '[REDACTED_LOCAL_CREDENTIAL]')
            if safe != original:
                path.write_text(safe)
                redacted.append(str(path.relative_to(ROOT)))
print('Credential redactions (paths only):', json.dumps(redacted))

sql = ("SELECT current_database(), current_user;\n"
       "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;\n"
       "SELECT id, marker FROM environment_checkpoint;\n"
       "SHOW listen_addresses;\nSHOW port;\nSHOW password_encryption;\n"
       "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname='agentcv_validation';\n")
result = pg(PG / 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', DATA,
            '-p', '55432', '-d', 'agentcv_validation', input=sql, text=True, stdout=subprocess.PIPE)
output = LOCAL / 'current-evidence'
output.mkdir(exist_ok=True)
(output / 'database.txt').write_text(result.stdout)
print(result.stdout)

files = []
for folder in (ROOT / 'src', ROOT / 'scripts', ROOT / 'frontend'):
    for path in folder.rglob('*'):
        if not path.is_file() or any(part in ('node_modules', 'dist', '__pycache__') for part in path.parts):
            continue
        files.append(path)
files += [ROOT / 'pom.xml', ROOT / 'README.md', ROOT / '.gitignore', ROOT / '.nvmrc']
manifest = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(files)}
(output / 'source-hashes.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print('Source files recorded:', len(manifest))
