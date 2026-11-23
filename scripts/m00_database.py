"""Dedicated M00 PostgreSQL. Never uses legacy credentials, database or volume."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local/m00'
NAME = 'agentcv-m00-postgres'


def command(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def prepare():
    LOCAL.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(LOCAL, 0o700)
    config = LOCAL / 'postgres.env'
    if not config.exists():
        with config.open('x') as output:
            os.chmod(config, 0o600)
            output.write('POSTGRES_USER=agentcv_m00\nPOSTGRES_DB=agentcv_m00\nPOSTGRES_PASSWORD=' + secrets.token_hex(24) + '\n')
    if config.stat().st_mode & 0o077:
        raise RuntimeError('M00 credentials must have mode 600')
    image = json.loads((ROOT / 'postgres17.lock.json').read_text())['image']
    existing = subprocess.run(['docker', 'inspect', NAME], capture_output=True, text=True)
    if existing.returncode:
        command('docker', 'run', '--pull=never', '-d', '--name', NAME, '--label', 'agentcv.scope=m00',
                '--env-file', str(config), '-p', '127.0.0.1:55434:5432', image)
    else:
        metadata = json.loads(existing.stdout)[0]
        binding = metadata['HostConfig']['PortBindings'].get('5432/tcp')
        if (metadata['Config']['Labels'].get('agentcv.scope') != 'm00'
                or metadata['Config']['Image'] != image
                or binding != [{'HostIp': '127.0.0.1', 'HostPort': '55434'}]):
            raise RuntimeError('Refusing unknown database container')
        if not metadata['State']['Running']:
            command('docker', 'start', NAME)
    for _ in range(60):
        ready = subprocess.run(['docker', 'exec', NAME, 'pg_isready', '-U', 'agentcv_m00', '-d', 'agentcv_m00'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if ready.returncode == 0:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('M00 PostgreSQL startup timeout')
    password = dict(line.split('=', 1) for line in config.read_text().splitlines())['POSTGRES_PASSWORD']
    if not all(character in '0123456789abcdef' for character in password):
        raise RuntimeError('Unexpected local credential format')
    sql = """DO $$ BEGIN
IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='agentcv_m00_app') THEN
CREATE ROLE agentcv_m00_app LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE;
END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS m00_app AUTHORIZATION agentcv_m00_app;
""" % password
    command('docker', 'exec', '-i', NAME, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
            '-U', 'agentcv_m00', '-d', 'agentcv_m00', input=sql, stdout=subprocess.DEVNULL)


def environment():
    values = dict(line.split('=', 1) for line in (LOCAL / 'postgres.env').read_text().splitlines())
    env = os.environ.copy()
    # Do not inherit old Spring database settings.
    for key in list(env):
        if key.startswith(('SPRING_DATASOURCE_', 'SPRING_FLYWAY_', 'SPRING_PROFILES_')):
            del env[key]
    env.update(SPRING_PROFILES_ACTIVE='m00', AGENTCV_M00_DB_PASSWORD=values['POSTGRES_PASSWORD'])
    return env


if __name__ == '__main__':
    prepare()