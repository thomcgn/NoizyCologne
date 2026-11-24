#!/usr/bin/env python3
"""Actual Caddy trust-boundary fixture in a disposable isolated Compose project."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def run(args, *, env):
    result = subprocess.run(args, env=env, capture_output=True, timeout=180, check=False)
    if result.returncode:
        raise RuntimeError(f'Trust fixture failed: {args[0]}; output withheld')
    return result.stdout


def main():
    project = 'noizy-api-boundary-' + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        work = Path(directory)
        env = {key: value for key, value in os.environ.items() if not key.startswith(('COMPOSE_', 'API_TRUST_', 'AUTH_SERVICE_', 'TEAM_'))}
        production = (ROOT / 'deploy/server/Caddyfile').read_text()
        snippet = production.split('(security_headers) {', 1)[1].split('\n}\n', 1)[0]
        team = production.split('tsa.noizycologne.de {', 1)[1].split('\n}\n', 1)[0]
        config = '(security_headers) {\n' + snippet + '\n}\n\nhttp://fixture.test {' + team.replace('frontend:3000', 'fixture:3000').replace('api:8080', 'fixture:3000') + '\n}\n'
        (work / 'Caddyfile').write_text(config)
        (work / 'server.mjs').write_text('''import http from 'node:http';
import os from 'node:os';
const address = Object.values(os.networkInterfaces()).flat().find(item => item?.family==='IPv4' && !item.internal)?.address;
function trustedAuth(request) {return request.headers['x-noizy-auth-key']===process.env.AUTH_SERVICE_SECRET && request.headers['x-noizy-client-ip']===address;}
http.createServer((request,response)=>{
 if(request.url==='/api/auth/verify'){response.writeHead(request.headers.cookie==='fixture-session=valid' && trustedAuth(request)?204:401);response.end();return;}
 if(request.url.startsWith('/api/v1/')){response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify({trusted:request.headers['x-noizy-api-key']===process.env.API_TRUST_SECRET && request.headers['x-noizy-proxy-host']==='tsa.noizycologne.de' && request.headers['x-noizy-proxy-proto']==='https'}));return;}
 if(request.url==='/fixture-proxy-check'){response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify({trusted:trustedAuth(request)}));return;}
 response.writeHead(404);response.end();
}).listen(3000,'0.0.0.0');
''')
        (work / 'server.mjs').chmod(0o644)
        (work / 'Caddyfile').chmod(0o644)
        (work / 'compose.yaml').write_text('''services:
  fixture:
    image: noizycologne-browser-worker:quality
    entrypoint: [node,/app/trust-fixture.mjs]
    read_only: true
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: 128m
    pids_limit: 64
    environment:
      API_TRUST_SECRET: isolated-proxy-key-for-caddy-fixture-at-least-32-characters
      AUTH_SERVICE_SECRET: isolated-auth-proxy-key-for-fixture-at-least-32-characters
    volumes: [./server.mjs:/app/trust-fixture.mjs:ro]
  caddy:
    image: caddy:2.10-alpine
    networks:
      default:
        aliases: [fixture.test]
    environment:
      API_TRUST_SECRET: isolated-proxy-key-for-caddy-fixture-at-least-32-characters
      AUTH_SERVICE_SECRET: isolated-auth-proxy-key-for-fixture-at-least-32-characters
    volumes: [./Caddyfile:/etc/caddy/Caddyfile:ro]
    mem_limit: 128m
    pids_limit: 64
networks:
  default:
    internal: true
''')
        compose = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(work / 'compose.yaml'), '-p', project]
        try:
            run([*compose, 'up', '-d', '--no-build'], env=env)
            def request(path, cookie=False, forged=False):
                headers = {'Host': 'fixture.test'}
                if cookie:
                    headers['Cookie'] = 'fixture-session=valid'
                if forged:
                    headers['X-Noizy-Api-Key'] = 'attacker-controlled-key'
                    headers['X-Noizy-Proxy-Host'] = 'evil.example'
                    headers['X-Noizy-Proxy-Proto'] = 'http'
                    headers['X-Noizy-Auth-Key'] = 'attacker-auth-key'
                    headers['X-Noizy-Client-IP'] = '203.0.113.17'
                    headers['X-Real-IP'] = '198.51.100.23'
                    headers['X-Forwarded-For'] = '192.0.2.66'
                script = "const response=await fetch(" + json.dumps('http://fixture.test' + path) + ",{headers:" + json.dumps(headers) + ",signal:AbortSignal.timeout(1500)});console.log(JSON.stringify([response.status,await response.text(),Object.fromEntries(response.headers)]));"
                return json.loads(run([*compose, 'exec', '-T', 'fixture', 'node', '--input-type=module', '-e', script], env=env))
            for _ in range(50):
                try:
                    if request('/api/v1/fixtures')[0] == 401:
                        break
                except RuntimeError:
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError('Caddy fixture did not become ready')
            if request('/api/v1/fixtures', forged=True)[0] != 401:
                raise RuntimeError('Forged service header bypassed session authentication')
            status, body, _ = request('/api/v1/fixtures', cookie=True, forged=True)
            if status != 200 or not json.loads(body)['trusted']:
                raise RuntimeError('Client header was not replaced with trusted internal identity')
            frontend_status, frontend_body, _ = request('/fixture-proxy-check', forged=True)
            if frontend_status != 200 or not json.loads(frontend_body)['trusted']:
                raise RuntimeError('Frontend proxy did not overwrite auth key and derive client IP')
            required = {'strict-transport-security': 'max-age=31536000',
                        'x-content-type-options': 'nosniff',
                        'referrer-policy': 'strict-origin-when-cross-origin',
                        'x-frame-options': 'DENY'}
            for path in ('/api/v1/fixtures', '/fixture-proxy-check'):
                _, _, actual = request(path, forged=True)
                for name, value in required.items():
                    if actual.get(name) != value:
                        raise RuntimeError(f'Security header missing on {path}: {name}')
                if 'permissions-policy' not in actual or 'content-security-policy-report-only' not in actual:
                    raise RuntimeError(f'Policy headers missing on {path}')
            if request('/internal/auth/validate', cookie=True, forged=True)[0] != 404:
                raise RuntimeError('Private authentication store was exposed')
            if request('/internal/maintenance/factory-reset', cookie=True)[0] != 404:
                raise RuntimeError('Private reset route was exposed')
            print('PASS: real Caddy requires session, overwrites API/auth headers and client IP, keeps reset and auth store private, emits security headers')
        finally:
            run([*compose, 'rm', '-sf'], env=env)
            networks = run(['docker', 'network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project], env=env).decode().split()
            for network in networks:
                run(['docker', 'network', 'rm', network], env=env)


if __name__ == '__main__':
    main()
