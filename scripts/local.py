#!/usr/bin/env python3
"""Explicit, project-local environment checkpoint commands. No global services."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import re
import signal
import time
import subprocess
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from passwords import bcrypt_hash, test_credentials

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local'
DATA = Path('/var/lib/postgresql/resumeai-validation')
PG = Path('/usr/lib/postgresql/12/bin')
NODE = Path('/root/.nvm/versions/node/v22.22.2/bin')


def require_linux():
    if sys.platform != 'linux':
        raise RuntimeError('Use Ubuntu WSL Python, not Windows Python. In PowerShell run: '
                           'wsl.exe -d Ubuntu -u root --exec /usr/bin/python3 '
                           '/root/java/ResumeAI/scripts/local.py status')


def listening(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=1):
            return True
    except OSError:
        return False


def application_pid():
    """Identify the actual listener, not an arbitrary Java process or stale PID file."""
    result = subprocess.run(['ss', '-H', '-ltnp', 'sport', '=', ':8080'],
                            check=True, text=True, stdout=subprocess.PIPE)
    pids = set(re.findall(r'pid=(\d+)', result.stdout))
    if len(pids) != 1:
        return None
    pid = int(next(iter(pids)))
    try:
        args = Path('/proc/' + str(pid) + '/cmdline').read_bytes().split(b'\0')
        jar = str(ROOT / 'target/agentcv-0.0.1-SNAPSHOT.jar').encode()
        if len(args) >= 3 and Path(os.fsdecode(args[0])).name == 'java' and args[1:3] == [b'-jar', jar]:
            return pid
    except (OSError, ValueError):
        pass
    return None


def stop_application():
    if not listening(8080):
        print('AgentCV is already stopped; database was not changed.')
        return
    pid = application_pid()
    if pid is None:
        raise RuntimeError('Port 8080 belongs to an unverified process; refusing to stop it. Inspect: ss -ltnp sport = :8080')
    # Recheck ownership immediately before signalling, never use a stored/stale PID.
    if application_pid() != pid:
        raise RuntimeError('Listener changed; refusing to stop it. Retry status.')
    os.kill(pid, signal.SIGTERM)
    for _ in range(150):
        if not listening(8080):
            print('AgentCV stopped gracefully; database remains running.')
            return
        time.sleep(0.1)
    raise RuntimeError('Shutdown has not released 8080 after 15 seconds; no force kill attempted. Inspect status/logs.')


def status():
    print('Runtime: ' + sys.executable + ' (' + sys.platform + ')')
    print('Project: ' + str(ROOT))
    print('Database 127.0.0.1:55432: ' + ('LISTENING (not an authentication check)' if listening(55432) else 'STOPPED'))
    print('Application 127.0.0.1:8080: ' + ('LISTENING (not a readiness check)' if listening(8080) else 'STOPPED'))
    print('Packaged JAR: ' + ('PRESENT' if (ROOT / 'target/agentcv-0.0.1-SNAPSHOT.jar').is_file() else 'MISSING; run frontend then verify'))
    print('Application log: ' + str(LOCAL / 'run.log'))
    print('Database log: ' + str(DATA / 'server.log'))
    if listening(8080):
        pid = application_pid()
        print('Listener: ' + ('verified AgentCV PID ' + str(pid) if pid else 'unverified process; will not stop automatically'))
        print('Open http://127.0.0.1:8080/ ; to stop this project: python3 ' + str(ROOT / 'scripts/local.py') + ' stop')


def run_application():
    jar = ROOT / 'target/agentcv-0.0.1-SNAPSHOT.jar'
    if not jar.is_file():
        raise RuntimeError('Packaged JAR missing; run frontend then verify')
    if not listening(55432):
        raise RuntimeError('Validation database is stopped. Run db-start first.')
    if listening(8080):
        pid = application_pid()
        if pid is not None:
            print('AgentCV is already running (PID ' + str(pid) + '). No second instance started.')
            print('Open http://127.0.0.1:8080/ . To reload a changed password/JAR: run local.py stop, then local.py run.')
            return
        raise RuntimeError('Port 8080 is already occupied; inspect status before starting. No process was stopped.')
    env = environment()
    path = LOCAL / 'run.log'
    print('Starting AgentCV in foreground: http://127.0.0.1:8080/ ; Ctrl+C to stop.', flush=True)
    print('Application output is also appended to ' + str(path), flush=True)
    with path.open('a') as log:
        log.write('\n--- foreground application start ---\n')
        log.flush()
        process = subprocess.Popen(['java', '-jar', str(jar)], cwd=str(ROOT), env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace')
        try:
            for line in process.stdout:
                print(line, end='', flush=True)
                log.write(line)
                log.flush()
            code = process.wait()
        except KeyboardInterrupt:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            print('\nApplication stopped; database remains separate.', flush=True)
            return
        finally:
            process.stdout.close()
        if code:
            raise RuntimeError('Application exited with code ' + str(code) + '; inspect ' + str(path))


def run(args, **kwargs):
    kwargs.setdefault('cwd', str(ROOT))
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def pg(*args, **kwargs):
    kwargs.setdefault('cwd', str(DATA))
    return run(['/usr/sbin/runuser', '-u', 'postgres', '--', *args], **kwargs)


def config():
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    path = LOCAL / 'credentials.json'
    if not path.exists():
        values = {key: secrets.token_hex(24) for key in ('admin', 'database')}
        values['operatorHash'] = bcrypt_hash(secrets.token_hex(12))
        fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(values, stream)
    if path.stat().st_mode & 0o077:
        raise RuntimeError('credentials.json must not be accessible to group/others')
    values = json.loads(path.read_text())
    if 'operator' in values:
        values['operatorHash'] = bcrypt_hash(values['operator'], legacy=True)
        del values['operator']
        # Preserve the same password while removing its cleartext persisted value.
        with path.open('w') as stream:
            json.dump(values, stream)
        print('Migrated operator credential to bcrypt-only storage; password unchanged.', flush=True)
    return values


def environment():
    values = config()
    env = os.environ.copy()
    env['AGENTCV_DB_PASSWORD'] = values['database']
    env['AGENTCV_OPERATOR_PASSWORD_HASH'] = values['operatorHash']
    env['PATH'] = str(NODE) + ':' + env['PATH']
    return env


def maven(*goals):
    # Maven does not inherit curl/npm proxy environment variables.
    settings = ET.Element('settings', xmlns='http://maven.apache.org/SETTINGS/1.0.0')
    proxy_url = os.environ.get('https_proxy') or os.environ.get('HTTPS_PROXY')
    if proxy_url:
        parsed = urllib.parse.urlsplit(proxy_url)
        if parsed.scheme != 'http' or not parsed.hostname:
            raise RuntimeError('Only an explicit HTTP CONNECT proxy is supported by this checkpoint')
        proxy = ET.SubElement(ET.SubElement(settings, 'proxies'), 'proxy')
        fields = {'id': 'checkpoint-environment', 'active': 'true', 'protocol': 'http',
                  'host': parsed.hostname, 'port': str(parsed.port or 80),
                  'nonProxyHosts': 'localhost|127.*|[::1]'}
        if parsed.username:
            fields['username'] = urllib.parse.unquote(parsed.username)
        if parsed.password:
            fields['password'] = urllib.parse.unquote(parsed.password)
        for key, value in fields.items():
            ET.SubElement(proxy, key).text = value
    path = LOCAL / 'maven-settings.xml'
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        ET.ElementTree(settings).write(stream, encoding='utf-8', xml_declaration=True)
    env = environment()
    if 'verify' in goals:
        env, _ = test_credentials(env)
    return run(['mvn', '-B', '-ntp', '-s', path,
                '-Dmaven.wagon.httpconnectionManager.ttlSeconds=20',
                '-Dmaven.wagon.rto=20000', '-Dmaven.wagon.http.retryHandler.count=1', *goals], env=env)


def psql(sql):
    return pg(PG / 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', DATA,
              '-p', '55432', '-d', 'postgres', input=sql, text=True)


def db_start():
    values = config()
    if DATA.is_symlink() or not DATA.is_dir():
        raise RuntimeError('Expected approved real database directory')
    if not (DATA / 'PG_VERSION').exists():
        if any(DATA.iterdir()):
            raise RuntimeError('Refusing to initialize nonempty unknown directory')
        pg(PG / 'initdb', '-D', DATA, '--encoding=UTF8', '--locale=C.UTF-8',
           '--auth-local=peer', '--auth-host=scram-sha-256')
        settings = ("\nlisten_addresses = '127.0.0.1'\nport = 55432\n"
                    "unix_socket_directories = '/var/lib/postgresql/resumeai-validation'\n"
                    "password_encryption = 'scram-sha-256'\n")
        with (DATA / 'postgresql.conf').open('a') as stream:
            stream.write(settings)
    if (DATA / 'PG_VERSION').read_text().strip() != '12':
        raise RuntimeError('Unexpected PostgreSQL data version')
    status = pg(PG / 'pg_ctl', '-D', DATA, 'status', stdout=subprocess.PIPE) if (DATA / 'postmaster.pid').exists() else None
    if status is None:
        pg(PG / 'pg_ctl', '-D', DATA, '-l', DATA / 'server.log', '-w', 'start')
    # Secrets are passed through stdin, never as command-line arguments.
    psql("SELECT 'CREATE ROLE agentcv_validation LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE' "
         "WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='agentcv_validation')\gexec\n"
         "ALTER ROLE agentcv_validation PASSWORD '" + values['database'] + "';\n"
         "SELECT 'CREATE DATABASE agentcv_validation OWNER agentcv_validation' "
         "WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='agentcv_validation')\gexec\n")
    print('Isolated database ready at 127.0.0.1:55432 (synthetic validation only).')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['db-start', 'db-stop', 'frontend', 'verify', 'run', 'stop', 'dependencies', 'status', 'set-password'])
    command = parser.parse_args().command
    require_linux()
    if command == 'status':
        status()
        return
    if command == 'set-password':
        import getpass
        password = getpass.getpass('New operator password (6-32 characters, hidden): ')
        if password != getpass.getpass('Repeat password: '):
            raise RuntimeError('Passwords do not match')
        values = config()
        values['operatorHash'] = bcrypt_hash(password)
        with (LOCAL / 'credentials.json').open('w') as stream:
            json.dump(values, stream)
        print('Operator bcrypt hash updated. Restart application to take effect.')
    elif command == 'db-start':
        db_start()
    elif command == 'db-stop':
        pg(PG / 'pg_ctl', '-D', DATA, '-m', 'fast', '-w', 'stop')
    elif command == 'frontend':
        env = environment()
        if run([NODE / 'node', '--version'], env=env).returncode != 0:
            raise RuntimeError('Required Node unavailable')
        npm = [NODE / 'npm', '--prefix', ROOT / 'frontend']
        run(npm + (['ci'] if (ROOT / 'frontend/package-lock.json').exists() else ['install'])
            + ['--no-fund', '--no-audit', '--fetch-retries=1', '--fetch-timeout=20000'], env=env)
        run(npm + ['run', 'build'], env=env)
    elif command == 'verify':
        if not (ROOT / 'frontend/dist/index.html').is_file():
            raise RuntimeError('Run frontend build before backend verify')
        environment()
        maven('verify')
    elif command == 'dependencies':
        environment()
        maven('dependency:tree', '-DoutputFile=.local/dependency-tree.txt')
    elif command == 'run':
        run_application()
    elif command == 'stop':
        stop_application()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        print('Checkpoint command failed: ' + str(error), file=sys.stderr)
        sys.exit(1)