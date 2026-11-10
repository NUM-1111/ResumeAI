"""Verify original rows verbatim without printing resume data; no writes."""
import hashlib
import json
import subprocess
from pathlib import Path
from local import ROOT, LOCAL
from postgres17 import NAME

tables=['environment_checkpoint','profile','workspace','material_import','resume_revision','current_resume','resume_source_binding','operation','recycle_entry','flyway_schema_history']
report={}
for table in tables:
    statement="SET timezone='UTC'; SELECT coalesce(json_agg(row_to_json(t)),'[]') FROM "+table+' t;'
    old=subprocess.check_output(['/usr/sbin/runuser','-u','postgres','--','/usr/lib/postgresql/12/bin/psql','-XAt','-h','/var/lib/postgresql/resumeai-validation','-p','55432','-d','agentcv_validation','-c',statement],text=True)
    new=subprocess.check_output(['docker','exec',NAME,'psql','-XAt','-U','postgres','-d','agentcv_validation','-c',statement],text=True)
    normalize=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False)
    originals={normalize(row) for row in json.loads(old.splitlines()[-1])}
    migrated={normalize(row) for row in json.loads(new.splitlines()[-1])}
    if not originals.issubset(migrated):raise RuntimeError('Original row mismatch: '+table)
    report[table]={'originalRows':len(originals),'allOriginalRowsUnchanged':True}
statement='SELECT storage_key,original_hash FROM material_import;'
rows=subprocess.check_output(['docker','exec',NAME,'psql','-XAt','-F','|','-U','postgres','-d','agentcv_validation','-c',statement],text=True)
for line in rows.splitlines():
    key,digest=line.split('|')
    if hashlib.sha256((LOCAL/'materials'/key).read_bytes()).hexdigest()!=digest:raise RuntimeError('Original file checksum mismatch')
(LOCAL/'migration17-verification.json').write_text(json.dumps(report,indent=2))
print('PASS: all original rows unchanged across '+str(len(tables))+' tables; all original file hashes match')