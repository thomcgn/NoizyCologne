import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { createHmac, createHash } from 'node:crypto';
import bcrypt from 'bcryptjs';
import ts from 'typescript';

const require = createRequire(import.meta.url);
const fixtureKey = 'fixture-auth-service-key-no-production-value';
const sessionSecret = 'fixture-session-signing-key-no-production-value';
const originalFetch = globalThis.fetch;
const originalEnv = { ...process.env };
let cookie;
let incoming;
let requests;
let rows;
let buckets;
let unavailable;
let malformed;
let auth;
function loadAuth() {
  const source = readFileSync(new URL('../src/lib/auth.ts', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true}}).outputText;
  const mod = {exports: {}};
  new Function('require', 'module', 'exports', compiled)(name => {
    if (name === 'server-only') return {};
    if (name === 'next/headers') return { cookies: async () => ({get: () => cookie ? {value: cookie} : undefined}), headers: async () => incoming };
    if (name === 'next/navigation') return { redirect: path => { throw new Error(`redirect:${path}`); } };
    return require(name);
  }, mod, mod.exports);
  return mod.exports;
}
function request(method = 'POST', extra = {}) {
  return new Request('https://tsa.noizycologne.de/api/auth/login', {method, headers: {
    'x-noizy-auth-key': fixtureKey, 'x-noizy-client-ip': '192.0.2.10',
    ...(method === 'POST' ? {origin: 'https://tsa.noizycologne.de', 'sec-fetch-site': 'same-origin'} : {}), ...extra,
  }});
}
function signed(payload) {
  const encoded = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const key = createHmac('sha256', sessionSecret).update('noizy-session-v2').digest();
  return `${encoded}.${createHmac('sha256', key).update(encoded).digest('base64url')}`;
}
function payload() { return JSON.parse(Buffer.from(cookie.split('.')[0], 'base64url').toString()); }

beforeEach(() => {
  process.env.TEAM_USER = 'fixture-team';
  process.env.TEAM_PASSWORD_HASH = bcrypt.hashSync('correct-fixture-password', 4);
  process.env.TEAM_SESSION_SECRET = sessionSecret;
  process.env.AUTH_SERVICE_SECRET = fixtureKey;
  process.env.NOIZY_INTERNAL_API_URL = 'http://api:8080';
  process.env.NOIZY_PUBLIC_ORIGIN = 'https://tsa.noizycologne.de';
  cookie = undefined; incoming = request().headers; requests = []; rows = new Map(); buckets = new Map(); unavailable = false; malformed = false;
  globalThis.fetch = async (url, options) => {
    requests.push({url: String(url), options});
    assert.equal(options.headers['X-Noizy-Auth-Key'], fixtureKey);
    assert.equal(options.cache, 'no-store');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal);
    if (unavailable) throw new Error('fixture network failure');
    if (malformed) return Response.json({wrong: true});
    const body = JSON.parse(options.body);
    switch (new URL(url).pathname) {
      case '/internal/auth/register': rows.set(body.nonceHash, {...body, revoked: false}); return Response.json({valid: true});
      case '/internal/auth/validate': { const row = rows.get(body.nonceHash); return Response.json({valid: Boolean(row && !row.revoked && row.generation === body.generation)}); }
      case '/internal/auth/revoke': if (rows.has(body.nonceHash)) rows.get(body.nonceHash).revoked = true; return Response.json({valid: false});
      case '/internal/auth/attempt': {
        const count = buckets.get(body.addressHash) ?? 0;
        buckets.set(body.addressHash, count + 1);
        return Response.json({allowed: count < 5, retryAfter: count < 5 ? 0 : 900});
      }
      case '/internal/auth/clear': buckets.delete(body.addressHash); return Response.json({valid: true});
      default: throw new Error('Unexpected auth operation');
    }
  };
  auth = loadAuth();
});
afterEach(() => { globalThis.fetch = originalFetch; process.env = {...originalEnv}; });

test('sessions register hashed nonces and survive a new module instance; revocations remain effective', async () => {
  cookie = await auth.createSession('fixture-team');
  const session = payload();
  const stored = [...rows.values()][0];
  assert.equal(stored.nonceHash, createHash('sha256').update(session.nonce).digest('hex'));
  assert.equal(stored.generation, session.generation);
  assert.equal(session.exp - session.iat, 8 * 3600);
  assert.ok(await loadAuth().getSession());
  await auth.revokeCurrentSession();
  assert.equal(await loadAuth().getSession(), null);
  assert.ok(!JSON.stringify([...rows]).includes(cookie));
});
test('password or signing-secret changes invalidate existing sessions', async () => {
  cookie = await auth.createSession('fixture-team');
  process.env.TEAM_PASSWORD_HASH = bcrypt.hashSync('new-password', 4);
  assert.equal(await auth.getSession(), null);
  process.env.TEAM_SESSION_SECRET = 'rotated-fixture-signing-secret-longer-than32';
  assert.equal(await auth.getSession(), null);
});
test('malformed, expired, wrong-account and extended-lifetime tokens are refused before store access', async () => {
  cookie = await auth.createSession('fixture-team');
  const valid = payload();
  for (const invalid of [
    cookie + '.extra', 'broken', 'a'.repeat(2049), signed({...valid, sub: 'other'}),
    signed({...valid, exp: Math.floor(Date.now()/1000)-1}), signed({...valid, exp: valid.exp+1}),
    signed({...valid, iat: valid.iat+120, exp: valid.exp+120}), signed({...valid, nonce: 'not-a-valid-nonce'}),
  ]) {
    cookie = invalid; requests = [];
    assert.equal(await auth.getSession(), null); assert.equal(requests.length, 0);
  }
});
test('store outage refuses session issuance, validation and confirmed logout', async () => {
  cookie = await auth.createSession('fixture-team'); unavailable = true;
  assert.equal(await auth.getSession(), null);
  await assert.rejects(auth.createSession('fixture-team'));
  await assert.rejects(auth.revokeCurrentSession());
});
test('untrusted direct calls and forged forwarded origins cannot authorize writes', async () => {
  assert.equal(auth.isSameOriginRequest(request()), true);
  assert.equal(auth.isSameOriginRequest(request('GET')), true);
  assert.equal(auth.isSameOriginRequest(request('POST', {'x-noizy-auth-key': 'wrong'})), false);
  assert.equal(auth.isSameOriginRequest(request('POST', {origin: '', 'sec-fetch-site': 'same-origin'})), false);
  assert.equal(auth.isSameOriginRequest(request('POST', {origin: 'https://evil.example', 'x-forwarded-host': 'evil.example', 'x-forwarded-proto': 'https'})), false);
  assert.equal(auth.isSameOriginRequest(request('POST', {'sec-fetch-site': 'same-site'})), false);
  incoming = new Headers();
  assert.equal(await auth.getSession(), null);
  await assert.rejects(auth.loginRateLimit(new Request('https://tsa.noizycologne.de/api/auth/login')));
});
test('username and spoofed IP changes share configured-account and trusted-address buckets', async () => {
  for (let index=0; index<5; index++) assert.equal((await auth.loginRateLimit(request('POST', {'x-real-ip': `192.0.2.${index}`, 'x-forwarded-for': `198.51.100.${index}`}))).allowed, true);
  assert.equal((await auth.loginRateLimit(request('POST', {'x-real-ip': '203.0.113.99'}))).allowed, false);
  const bodies = requests.map(item => JSON.parse(item.options.body));
  assert.equal(new Set(bodies.map(body => body.accountHash)).size, 1);
  assert.equal(new Set(bodies.map(body => body.addressHash)).size, 1);
  assert.match(bodies[0].accountHash, /^[0-9a-f]{64}$/);
  assert.ok(!JSON.stringify(bodies).includes('192.0.2'));
  await auth.clearLoginRateLimit(request());
  assert.equal((await auth.loginRateLimit(request())).allowed, true);
});
test('invalid trusted address falls into one bounded fallback bucket', async () => {
  await auth.loginRateLimit(request('POST', {'x-noizy-client-ip': 'bad-a'}));
  await auth.loginRateLimit(request('POST', {'x-noizy-client-ip': 'bad-b'}));
  assert.equal(JSON.parse(requests[0].options.body).addressHash, JSON.parse(requests[1].options.body).addressHash);
});
test('malformed store confirmation never issues a cookie or accepts a rate limit', async () => {
  malformed = true;
  await assert.rejects(auth.createSession('fixture-team'));
  await assert.rejects(auth.loginRateLimit(request()));
});
test('password comparison and cookie flags preserve the configured account and eight-hour session', async () => {
  assert.equal(await auth.authenticate('fixture-team', 'correct-fixture-password'), true);
  assert.equal(await auth.authenticate('other', 'correct-fixture-password'), false);
  assert.equal(await auth.authenticate('fixture-team', 'wrong'), false);
  assert.deepEqual(auth.sessionCookieOptions(), {httpOnly: true, secure: true, sameSite: 'lax', path: '/', maxAge: 28800});
  await assert.rejects(auth.createSession('other'));
});
test('internal endpoint validation refuses external targets and invalid origin configuration', async () => {
  process.env.NOIZY_INTERNAL_API_URL = 'https://evil.example';
  await assert.rejects(auth.createSession('fixture-team'));
  assert.equal(requests.length, 0);
  process.env.NOIZY_PUBLIC_ORIGIN = 'https://tsa.noizycologne.de/path';
  assert.equal(auth.isSameOriginRequest(request()), false);
});

function loadRoute(name) {
  const source = readFileSync(new URL(`../src/app/api/auth/${name}/route.ts`, import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true}}).outputText;
  class NextResponse extends Response {
    constructor(body, options) { super(body, options); this.cookies = {set: (name, value) => this.headers.append('set-cookie', `${name}=${value}`)}; }
    static json(body, options) { return new NextResponse(JSON.stringify(body), options); }
  }
  const mod = {exports: {}};
  new Function('require', 'module', 'exports', compiled)(name => name === 'next/server' ? {NextResponse}
    : name === '@/lib/auth' ? auth : require(name), mod, mod.exports);
  return mod.exports;
}
function loginRequest(username = 'fixture-team', password = 'correct-fixture-password', extra = {}) {
  return new Request('https://tsa.noizycologne.de/api/auth/login', {method: 'POST', headers: {
    ...Object.fromEntries(request().headers), 'content-type': 'application/json', ...extra,
  }, body: JSON.stringify({username, password})});
}
test('login route issues a cookie only after confirmed registration and returns no-store failures', async () => {
  const route = loadRoute('login');
  const success = await route.POST(loginRequest());
  assert.equal(success.status, 200); assert.equal(success.headers.get('cache-control'), 'no-store');
  assert.match(success.headers.get('set-cookie'), /^noizy_team_session=.+/);
  unavailable = true;
  const failure = await route.POST(loginRequest());
  assert.equal(failure.status, 503); assert.equal(failure.headers.get('set-cookie'), null);
  assert.equal(failure.headers.get('cache-control'), 'no-store');
});
test('login rejects oversized and non-JSON requests and varying usernames do not bypass five attempts', async () => {
  const route = loadRoute('login');
  assert.equal((await route.POST(loginRequest('fixture-team', 'x'.repeat(5000)))).status, 400);
  assert.equal((await route.POST(loginRequest('fixture-team', 'wrong', {'content-type': 'text/plain'}))).status, 415);
  assert.equal(requests.length, 0);
  for (let index=0; index<5; index++) assert.equal((await route.POST(loginRequest(`attacker-${index}`, 'wrong', {'x-real-ip': `198.51.100.${index}`}))).status, 401);
  const limited = await route.POST(loginRequest('fixture-team', 'correct-fixture-password'));
  assert.equal(limited.status, 429); assert.equal(limited.headers.get('retry-after'), '900');
  assert.equal(new Set(requests.map(item => JSON.parse(item.options.body).accountHash)).size, 1);
});
test('logout refuses false success on store outage and replay is rejected after confirmed revocation', async () => {
  cookie = await auth.createSession('fixture-team');
  const route = loadRoute('logout');
  unavailable = true;
  const failed = await route.POST(request());
  assert.equal(failed.status, 503); assert.equal(failed.headers.get('set-cookie'), null);
  unavailable = false;
  assert.equal((await loadRoute('verify').GET()).status, 204);
  const success = await route.POST(request());
  assert.equal(success.status, 200); assert.equal(success.headers.get('set-cookie'), 'noizy_team_session=');
  // Cookie deliberately remains in the request fixture to model replay of a copied token.
  const replay = await loadRoute('verify').GET();
  assert.equal(replay.status, 401); assert.equal(replay.headers.get('cache-control'), 'no-store');
});
test('login and logout reject cross-origin and direct requests before contacting the store', async () => {
  for (const route of ['login', 'logout']) {
    assert.equal((await loadRoute(route).POST(loginRequest('fixture-team', 'correct-fixture-password', {origin: 'https://evil.example'}))).status, 403);
    assert.equal((await loadRoute(route).POST(loginRequest('fixture-team', 'correct-fixture-password', {'x-noizy-auth-key': 'forged'}))).status, 403);
  }
  assert.equal(requests.length, 0);
});

test('version-separated signatures keep new cookies invalid in the legacy stateless format', async () => {
  cookie = await auth.createSession('fixture-team');
  const [encoded, current] = cookie.split('.');
  const legacy = createHmac('sha256', sessionSecret).update(encoded).digest('base64url');
  assert.notEqual(current, legacy, 'Legacy verifier must reject a newly issued cookie');
  cookie = `${encoded}.${legacy}`;
  requests = [];
  assert.equal(await auth.getSession(), null);
  assert.equal(requests.length, 0);
});
