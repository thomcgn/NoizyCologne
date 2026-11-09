import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const require = createRequire(import.meta.url);
const secret = 'a-separate-server-to-server-key-with-more-than-32-chars';
process.env.FACTORY_RESET_SECRET = secret;
process.env.NOIZY_INTERNAL_API_URL = 'http://api:8080';

let signedIn = true;
let allowPassword = true;
let requests = [];
const nativeFetch = globalThis.fetch;
globalThis.fetch = async (url, options) => {
  requests.push({ url, options });
  return Response.json(options.method === 'POST'
    ? { deletedRecords: 3, deletedFiles: 2, filesNotDeleted: 0, partial: false }
    : { tables: [], recordCount: 3, mediaFileCount: 2 });
};
const auth = {
  getSession: async () => signedIn ? { sub: 'team', nonce: 'session-nonce' } : null,
  authenticate: async (username, password) => username === 'team' && password === 'correct' && allowPassword,
  isSameOriginRequest: request => request.headers.get('origin') !== 'https://evil.example',
  loginRateLimit: () => ({ allowed: true, retryAfter: 0 }),
  clearLoginRateLimit: () => {},
};
const source = readFileSync(new URL('../src/app/api/admin/factory-reset/route.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
const compiledModule = { exports: {} };
new Function('require', 'module', 'exports', compiled)(
  name => name === 'next/server' ? { NextResponse: { json: (body, init) => Response.json(body, init) } }
    : name === '@/lib/auth' ? auth : require(name),
  compiledModule, compiledModule.exports,
);
const { GET, POST } = compiledModule.exports;
function request(method, data, origin = 'https://tsa.noizycologne.de') {
  return new Request('https://tsa.noizycologne.de/api/admin/factory-reset', {
    method,
    headers: { origin, ...(method === 'POST' ? { 'content-type': 'application/json' } : {}) },
    ...(method === 'POST' ? { body: JSON.stringify(data) } : {}),
  });
}
afterEach(() => {
  signedIn = true; allowPassword = true; requests = [];
  process.env.FACTORY_RESET_SECRET = secret;
});

test('reset route blocks unsigned sessions and cross-origin attempts', async () => {
  signedIn = false;
  assert.equal((await GET(request('GET'))).status, 401);
  signedIn = true;
  assert.equal((await GET(request('GET', null, 'https://evil.example'))).status, 403);
  assert.equal((await POST(request('POST', { phrase: 'Kompletter Reset', password: 'correct', challenge: 'x' }, 'https://evil.example'))).status, 403);
  assert.equal(requests.length, 0);
});

test('reset requires server configuration and never contacts backend without it', async () => {
  delete process.env.FACTORY_RESET_SECRET;
  assert.equal((await GET(request('GET'))).status, 503);
  assert.equal(requests.length, 0);
});

test('one-use short-lived preview, exact phrase and password are mandatory', async () => {
  const preview = await GET(request('GET'));
  assert.equal(preview.status, 200);
  const { challenge } = await preview.json();
  assert.ok(challenge);
  assert.equal(requests.length, 1);
  assert.equal((await POST(request('POST', { challenge, password: 'correct', phrase: 'RESET' }))).status, 400);
  assert.equal(requests.length, 1);
  allowPassword = false;
  assert.equal((await POST(request('POST', { challenge, password: 'wrong', phrase: 'Kompletter Reset' }))).status, 401);
  assert.equal(requests.length, 1);
  // Authentication attempts consume their challenge; obtain a new preview.
  allowPassword = true;
  const second = await (await GET(request('GET'))).json();
  const success = await POST(request('POST', {
    challenge: second.challenge, password: 'correct', phrase: 'Kompletter Reset',
  }));
  assert.equal(success.status, 200);
  assert.equal(requests.length, 3);
  assert.equal(requests[2].url, 'http://api:8080/internal/maintenance/factory-reset');
  assert.equal(requests[2].options.headers['X-Noizy-Reset-Key'], secret);
  assert.equal((await POST(request('POST', {
    challenge: second.challenge, password: 'correct', phrase: 'Kompletter Reset',
  }))).status, 409);
  assert.equal(requests.length, 3);
});
process.on('exit', () => { globalThis.fetch = nativeFetch; });
