"""Local launcher: no PowerShell scripts, no persistent system setting changes."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.runtime'
CREATE_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            text = line.strip()
            if not text or text.startswith('#'):
                continue
            key, sep, value = text.partition('=')
            if not sep or not key.replace('_', '').isalnum():
                raise ValueError(f'Invalid config entry in {path.name}')
            values[key] = value.strip().strip('"').strip("'")
    return values


def setup():
    RUNTIME.mkdir(exist_ok=True)
    env_path = ROOT / '.env'
    if not env_path.exists():
        values = read_env(ROOT / '.env.example')
        for key, value in values.items():
            if not value:
                values[key] = secrets.token_urlsafe(24)
        env_path.write_text(''.join(f'{key}={value}\n' for key, value in values.items()), encoding='utf-8')
    values = read_env(env_path)
    required = ('POSTGRES_PASSWORD', 'S3_SECRET_KEY', 'SERVICE_TOKEN', 'ADMIN_PASSWORD', 'AGENT_PASSWORD', 'EMPLOYEE_PASSWORD', 'HR_PASSWORD')
    if not all(values.get(key) for key in required):
        raise ValueError('Root .env has empty required secrets; fill them locally or remove the unused initial file before setup.')
    accounts = '本机初始账户（私密文件，请勿提交或转发）\n\n'
    for username, key, role in [('admin','ADMIN_PASSWORD','管理员'), ('agent','AGENT_PASSWORD','IT处理员'), ('employee','EMPLOYEE_PASSWORD','IT员工'), ('hr','HR_PASSWORD','HR员工')]:
        accounts += f'{username} / {values[key]} / {role}\n'
    (RUNTIME / 'accounts.txt').write_text(accounts, encoding='utf-8')
    ai_path = ROOT / 'ai-service' / '.env'
    if not ai_path.exists():
        ai_path.parent.mkdir(exist_ok=True)
        ai_path.write_text('AGENT_LLM_API_KEY=\nAGENT_LLM_MODEL=\nAGENT_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1\nAGENT_EMBEDDING_MODEL=text-embedding-v3\nAGENT_EMBEDDING_DIMENSIONS=1024\n', encoding='utf-8')
    return values


def environment(values):
    env = os.environ.copy()
    env.update(values)
    user, password = values['POSTGRES_USER'], values['POSTGRES_PASSWORD']
    host = f"127.0.0.1:{values['POSTGRES_PORT']}"
    database = values['POSTGRES_DB']
    env.update({
        'DB_URL': f'jdbc:postgresql://{host}/{database}',
        'DB_USERNAME': user, 'DB_PASSWORD': password,
        'DATABASE_URL': f'postgresql://{urllib.parse.quote(user, safe="")}:{urllib.parse.quote(password, safe="")}@{host}/{database}',
        'REDIS_URL': f"redis://127.0.0.1:{values['REDIS_PORT']}/0",
        'S3_ENDPOINT': f"http://127.0.0.1:{values['S3_PORT']}", 'S3_BUCKET': 'helpdesk',
        'AI_BASE_URL': 'http://127.0.0.1:8101', 'AI_SERVICE_TOKEN': values['SERVICE_TOKEN'],
        'SERVER_PORT': '8180', 'PYTHONUTF8': '1',
    })
    java_candidates = [Path(env['JAVA_HOME'])] if env.get('JAVA_HOME') else []
    java_candidates += sorted((ROOT.parent / '.tools').glob('jdk-25*'), reverse=True)
    for candidate in java_candidates:
        executable = candidate / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if executable.exists():
            result = subprocess.run([str(executable), '-version'], capture_output=True, text=True, creationflags=CREATE_FLAGS)
            if 'version "25' in result.stderr:
                env['JAVA_HOME'] = str(candidate)
                env['PATH'] = str(candidate / 'bin') + os.pathsep + env.get('PATH', '')
                break
    return env


def docker_path():
    found = shutil.which('docker')
    if found:
        return found
    for candidate in [Path('D:/Program Files/Docker/resources/bin/docker.exe'), Path('C:/Program Files/Docker/Docker/resources/bin/docker.exe')]:
        if candidate.exists():
            return str(candidate)
    raise RuntimeError('Docker CLI not found. Start/install Docker Desktop.')


def run(command, cwd=ROOT, env=None):
    subprocess.run([str(item) for item in command], cwd=cwd, env=env, check=True, creationflags=CREATE_FLAGS)


def python_path():
    return ROOT / 'ai-service' / '.venv' / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')


def build(env):
    py = python_path()
    if not py.exists():
        run([sys.executable, '-m', 'venv', str(py.parents[1])])
    requirements = ROOT / 'ai-service' / 'requirements.txt'
    run([py, '-m', 'pip', 'install', '-r', requirements], env=env)
    mvn = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if not mvn:
        raise RuntimeError('Maven missing. Install Maven 3.9+ and Java 25 first.')
    maven_args = [mvn, '-B', '-ntp', f'-Dmaven.repo.local={ROOT / ".local" / "m2"}']
    settings_file = ROOT/'backend/maven-settings.xml'
    if settings_file.exists():
        maven_args += ['-s', str(settings_file)]
    run(maven_args + ['package'], cwd=ROOT/'backend', env=env)
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
    if not npm:
        raise RuntimeError('Node/npm missing. Install Node 22+.')
    run([npm, 'install', '--no-audit', '--no-fund'], cwd=ROOT/'frontend', env=env)
    run([npm, 'run', 'build'], cwd=ROOT/'frontend', env=env)


def listening(port):
    with socket.socket() as client:
        client.settimeout(0.5)
        return client.connect_ex(('127.0.0.1', port)) == 0


def health(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            raw = response.read()
            if response.headers.get_content_type() == 'application/json':
                return json.loads(raw)
            return {'status': response.status}
    except (OSError, ValueError, urllib.error.URLError):
        return None


SERVICES = [('ai',8101,'http://127.0.0.1:8101/health'), ('backend',8180,'http://127.0.0.1:8180/api/health'), ('frontend',5180,'http://127.0.0.1:5180')]


def owns_process(record):
    if os.name != 'nt':
        return False
    pid = int(record['pid'])
    query = f'$p = Get-CimInstance Win32_Process -Filter "ProcessId = {pid}"; if ($p) {{ @{{command=$p.CommandLine; created=([DateTimeOffset]$p.CreationDate).ToUnixTimeSeconds()}} | ConvertTo-Json -Compress }}'
    result = subprocess.run(['powershell.exe','-NoProfile','-Command',query],capture_output=True,text=True,creationflags=CREATE_FLAGS)
    if result.returncode or not result.stdout.strip():
        return False
    process = json.loads(result.stdout)
    # Creation time protects against OS PID reuse. Never print command lines.
    return str(ROOT).lower() in (process.get('command') or '').lower() and abs(process['created']-record['startedAt']) < 5


def start_process(name, command, cwd, env, port, url):
    marker = RUNTIME / f'{name}.json'
    if listening(port):
        if marker.exists() and owns_process(json.loads(marker.read_text(encoding='utf-8'))) and health(url):
            print(f'{name}: already listening at {url}', flush=True)
            return
        raise RuntimeError(f'Port {port} is occupied; stop its owner before starting {name}.')
    with (RUNTIME / f'{name}.log').open('ab', buffering=0) as log:
        child = subprocess.Popen([str(item) for item in command], cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=CREATE_FLAGS)
    marker.write_text(json.dumps({'pid':child.pid,'command':[str(item) for item in command],'startedAt':time.time()}),encoding='utf-8')
    deadline = time.monotonic()+120
    while time.monotonic()<deadline:
        if health(url):
            print(f'{name}: ready at {url}', flush=True)
            return
        if child.poll() is not None:
            raise RuntimeError(f'{name} exited. Read .runtime/{name}.log locally (may contain diagnostic details).')
        time.sleep(1)
    raise RuntimeError(f'{name} did not become ready. Read .runtime/{name}.log.')


def start(env, rebuild=False):
    run([docker_path(), 'compose', 'up', '-d', '--wait', '--wait-timeout', '180'], env=env)
    if rebuild or not (ROOT/'backend/target/helpdesk.jar').exists() or not (ROOT/'frontend/dist/index.html').exists() or not python_path().exists():
        build(env)
    py = python_path()
    start_process('ai', [py,'-X','utf8','-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8101'], ROOT/'ai-service',env,8101,SERVICES[0][2])
    java = Path(env['JAVA_HOME'])/'bin/java.exe' if env.get('JAVA_HOME') and os.name=='nt' else 'java'
    deployed_jar = RUNTIME/'helpdesk.jar'
    if not listening(8180):
        shutil.copy2(ROOT/'backend/target/helpdesk.jar', deployed_jar)
    start_process('backend',[java,'-Dfile.encoding=UTF-8','-jar',deployed_jar],ROOT,env,8180,SERVICES[1][2])
    node = shutil.which('node')
    start_process('frontend',[node, ROOT/'frontend/node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5180','--strictPort'],ROOT/'frontend',env,5180,SERVICES[2][2])
    print('Open http://127.0.0.1:5180 | Accounts: .runtime/accounts.txt | Model: ai-service/.env')


def stop_services():
    # Stop only processes whose command line contains this exact project directory.
    for name, _, _ in reversed(SERVICES):
        marker = RUNTIME / f'{name}.json'
        if not marker.exists():
            continue
        record = json.loads(marker.read_text(encoding='utf-8'))
        pid = int(record['pid'])
        if os.name != 'nt':
            raise RuntimeError('Automatic stop currently supports Windows only; stop verified processes manually.')
        if owns_process(record):
            run(['powershell.exe','-NoProfile','-Command',f'Stop-Process -Id {pid} -Force'])
        marker.unlink(missing_ok=True)
        print(f'{name}: stopped if owned by this project')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action',choices=['setup','build','start','stop','restart','status'])
    parser.add_argument('--build',action='store_true')
    args = parser.parse_args()
    values = setup()
    env = environment(values)
    if args.action == 'setup':
        print('Private local settings prepared. Initial accounts are in .runtime/accounts.txt.')
    elif args.action == 'build':
        build(env)
    elif args.action == 'start':
        start(env,args.build)
    elif args.action == 'stop':
        stop_services()
        run([docker_path(),'compose','stop'],env=env)
    elif args.action == 'restart':
        stop_services()
        start(env,args.build)
    else:
        for name,_,url in SERVICES:
            value = health(url)
            print(f'{name}: {json.dumps(value, ensure_ascii=False) if value else "unavailable"}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'Operation failed: {error}',file=sys.stderr)
        sys.exit(1)
