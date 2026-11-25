#!/usr/bin/env python3
"""Build and smoke-test the data-free skeleton, without legacy credentials or DB."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import m00_database
from model_environment import load_model_environment
import getpass
from passwords import bcrypt_hash, test_credentials

ROOT = Path(__file__).resolve().parents[1]
NODE = Path('/root/.nvm/versions/node/v22.22.2/bin')
JAR = ROOT / 'target/agentcv-0.0.1-SNAPSHOT.jar'


def environment():
    env = load_model_environment(m00_database.environment(), ROOT / '.env')
    # This host's OpenJDK 17.0.15 crashed in C2 PhaseLive/Register_Allocate.
    # Apply to Maven and every child JVM, including smoke/recovery processes.
    options = env.get('JAVA_TOOL_OPTIONS', '').strip()
    env['JAVA_TOOL_OPTIONS'] = (options + ' -XX:TieredStopAtLevel=1').strip()
    env['PATH'] = str(NODE) + ':' + env['PATH']
    password_file = ROOT / '.local/m00/operator.hash'
    if password_file.exists():
        if password_file.stat().st_mode & 0o077:
            raise RuntimeError('operator.hash must have mode 600')
        env['AGENTCV_OPERATOR_PASSWORD_HASH'] = password_file.read_text().strip()
    return env


def run(*args, cwd=ROOT):
    env = environment()
    if 'verify' in args:
        env, _ = test_credentials(env)
        env['AGENTCV_DISABLE_AI_WORKER'] = 'true'
        env.pop('DEEPSEEK_API_KEY', None)
        env['SPRING_DATASOURCE_URL']='jdbc:postgresql://127.0.0.1:55434/agentcv_m00?currentSchema=m00_test'
        env['SPRING_FLYWAY_DEFAULT_SCHEMA']='m00_test'
        env['AGENTCV_FILES_ROOT']=str(ROOT / '.local/m00-test/resume-files')
    subprocess.run(args, cwd=cwd, env=env, check=True)


def verify():
    m00_database.prepare()
    subprocess.run(['docker','exec','agentcv-m00-postgres','psql','-X','-v','ON_ERROR_STOP=1','-U','agentcv_m00','-d','agentcv_m00','-c',
                    'CREATE SCHEMA IF NOT EXISTS m00_test AUTHORIZATION agentcv_m00_app'],check=True,stdout=subprocess.DEVNULL)
    run(str(NODE / 'npm'), 'ci', cwd=ROOT / 'frontend')
    run(str(NODE / 'npm'), 'run', 'build', cwd=ROOT / 'frontend')
    # clean is essential: deleted legacy classes/resources must not survive in the JAR.
    run('mvn', '-B', '-ntp', '-o', 'clean', 'verify')


def smoke(only=None):
    m00_database.prepare()
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', 18081)) == 0:
            raise RuntimeError('Smoke port 18081 is occupied; no process was stopped')
    log_path = ROOT / '.local/skeleton-smoke.log'
    log_path.parent.mkdir(exist_ok=True)
    with log_path.open('w') as log:
        test_env, test_password = test_credentials(environment())
        # Ordinary browser regressions must never spend a configured model key.
        test_env.pop('DEEPSEEK_API_KEY', None)
        test_env['AGENTCV_DISABLE_AI_WORKER']='true'
        test_env['SPRING_DATASOURCE_URL']='jdbc:postgresql://127.0.0.1:55434/agentcv_m00?currentSchema=m00_test'
        test_env['SPRING_FLYWAY_DEFAULT_SCHEMA']='m00_test'
        test_env['AGENTCV_FILES_ROOT']=str(ROOT / '.local/m00-test/resume-files')
        process = subprocess.Popen(['java', '-jar', str(JAR), '--server.port=18081'],
                                   cwd=ROOT, env=test_env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError('Application exited; inspect skeleton-smoke.log')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:18081/api/v1/system/status', timeout=1) as response:
                        assert json.load(response)['stage'] == 'skeleton'
                    break
                except OSError:
                    time.sleep(0.2)
            else:
                raise RuntimeError('Application startup timed out')
            with urllib.request.urlopen('http://127.0.0.1:18081/', timeout=2) as response:
                assert 'id="app"' in response.read().decode()
            from browser_m01 import verify as browser_verify
            if only is None or only == 'm01':
                browser_verify(18081, test_password)
            from browser_m02 import verify as browser_m02_verify
            if only is None or only == 'm02':
                browser_m02_verify(18081, test_password)
            if only is None or only == 'm03':
                from browser_m03 import verify as browser_m03_verify
                browser_m03_verify(18081,test_password)
            if only is None or only == 'm04-layout':
                from browser_m04_layout import verify as layout_verify
                layout_verify(18081,test_password)
            if only is None or only == 'm05':
                from browser_m05 import verify as m05_verify
                m05_verify(18081,test_password)
            if only is None or only == 'm06':
                from browser_m06 import verify as m06_verify
                m06_verify(18081,test_password)
            if only is None or only == 'm07':
                from browser_m07 import verify as m07_verify
                m07_verify(18081,test_password)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['verify', 'smoke', 'run', 'set-password'])
    parser.add_argument('--slice', choices=['m01','m02','m03','m04-layout','m05','m06','m07'], help='Run one browser slice; omitted runs ordinary business slices')
    options = parser.parse_args()
    command = options.command
    if command == 'verify':
        verify()
    elif command == 'smoke':
        smoke(options.slice)
    elif command == 'set-password':
        value = getpass.getpass('设置 operator 口令（6—32字符）：')
        if value != getpass.getpass('再次输入：'):
            raise RuntimeError('两次输入不一致，未更改口令')
        hashed = bcrypt_hash(value)
        folder = ROOT / '.local/m00'
        folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        path = folder / 'operator.hash'
        fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as output:
            output.write(hashed)
        print('口令已保存为散列；重启应用后生效。')
    else:
        if not (ROOT / '.local/m00/operator.hash').exists():
            raise RuntimeError('先执行 set-password 设置新工程口令；不读取旧凭据')
        m00_database.prepare()
        run('java', '-jar', str(JAR))