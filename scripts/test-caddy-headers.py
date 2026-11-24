#!/usr/bin/env python3
"""Check security headers in Caddy's rendered configuration, without a live host."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CADDYFILE = ROOT / 'deploy/server/Caddyfile'
HOSTS = ('noizycologne.de', 'www.noizycologne.de', 'tsa.noizycologne.de', 'api.noizycologne.de')
EXPECTED = {
    'Strict-Transport-Security': 'max-age=31536000',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'X-Frame-Options': 'DENY',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), accelerometer=(), gyroscope=(), magnetometer=(), fullscreen=(self)',
    'Content-Security-Policy-Report-Only': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
}


def render():
    command = ['docker', 'run', '--rm',
               '-e', 'ACME_EMAIL=ci@example.invalid',
               '-e', 'AUTH_SERVICE_SECRET=isolated-auth-service-fixture-key-never-production',
               '-e', 'API_TRUST_SECRET=isolated-ci-caddy-api-key-at-least-32-characters',
               '-v', f'{CADDYFILE}:/etc/caddy/Caddyfile:ro',
               'caddy:2.10-alpine', 'caddy', 'adapt',
               '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile']
    result = subprocess.run(command, capture_output=True, text=True, timeout=180, check=False)
    if result.returncode:
        # Fixture secrets must never be echoed; the CI validate step shows diagnostics.
        raise RuntimeError('Caddy adapt failed; output withheld')
    return json.loads(result.stdout)


def header_sets(node):
    found = []
    if isinstance(node, dict):
        if node.get('handler') == 'headers':
            found.append(node.get('response', {}).get('set', {}))
        for value in node.values():
            found.extend(header_sets(value))
    elif isinstance(node, list):
        for value in node:
            found.extend(header_sets(value))
    return found


def main():
    routes = render()['apps']['http']['servers']['srv0']['routes']
    rendered = {}
    for route in routes:
        hosts = [host for match in route.get('match', []) for host in match.get('host', [])]
        for host in hosts:
            rendered.setdefault(host, []).extend(header_sets(route))
    if set(rendered) != set(HOSTS):
        raise RuntimeError(f'Unexpected public vhosts in Caddy config: {sorted(rendered)}')
    for host in HOSTS:
        sets = rendered[host]
        if len(sets) != 1:
            raise RuntimeError(f'{host}: expected exactly one security header handler, got {len(sets)}')
        values = {name: (items[0] if len(items) == 1 else items) for name, items in sets[0].items()}
        for name, expected in EXPECTED.items():
            if values.get(name) != expected:
                raise RuntimeError(f'{host}: missing or changed header {name}')
        if any(name.lower() == 'content-security-policy' for name in values):
            raise RuntimeError(f'{host}: blocking CSP must not be enabled')
        hsts = values['Strict-Transport-Security']
        if 'includeSubDomains' in hsts or 'preload' in hsts:
            raise RuntimeError(f'{host}: includeSubDomains/preload need VPS verification first')
    print('PASS: all public vhosts render the required security headers; CSP is report-only')


if __name__ == '__main__':
    main()
