"""Read-only database/schema and Git checkpoint inspection; no credentials needed."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
def run(args, **kwargs):
    return subprocess.run(args, cwd='/tmp', check=True, text=True, **kwargs)

run(['git', '-C', str(ROOT), 'status', '--short', '--branch'])
run(['git', '-C', str(ROOT), 'log', '-3', '--oneline'])
sql = """
BEGIN READ ONLY;
SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'profile' AS object,count(*) FROM profile
UNION ALL SELECT 'workspace',count(*) FROM workspace
UNION ALL SELECT 'material_import',count(*) FROM material_import
UNION ALL SELECT 'current_resume',count(*) FROM current_resume;
SELECT count(*) AS inconsistent_current_revision_links FROM current_resume c
LEFT JOIN resume_revision r USING(workspace_id,profile_id,revision) WHERE r.revision IS NULL;
SHOW listen_addresses;
SHOW port;
COMMIT;
"""
from postgres17 import sql as database_sql
print(database_sql(sql))