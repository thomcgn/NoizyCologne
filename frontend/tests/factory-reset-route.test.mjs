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
let nonce = "session-nonce";
let revision = "a".repeat(64);
let fetchOverride = null;
const nativeFetch = globalThis.fetch;
globalThis.fetch = async (url, options) => {
  requests.push({ url, options });
  if (fetchOverride) return fetchOverride(url, options);
  return Response.json(options.method === 'POST'
    ? { deletedRecords: 3, deletedFiles: 2, filesNotDeleted: 0, partial: false }
    : { tables: [], recordCount: 3, mediaFileCount: 2, revision });
};
const auth = {
  getSession: async () => signedIn ? { sub: 'team', nonce } : null,
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
  signedIn = true; allowPassword = true; requests = []; nonce = "session-nonce"; revision = "a".repeat(64); fetchOverride = null;
  process.env.NOIZY_INTERNAL_API_URL = "http://api:8080";
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


test('reset uses the server-held revision even when the client supplies another one', async () => {
  const {challenge} = await (await GET(request('GET'))).json();
  const response = await POST(request('POST',{challenge,password:'correct',phrase:'Kompletter Reset',revision:'b'.repeat(64)}));
  assert.equal(response.status,200);
  assert.deepEqual(JSON.parse(requests.at(-1).options.body),{phrase:'Kompletter Reset',revision:'a'.repeat(64)});
  assert.equal(requests.at(-1).options.redirect,'error');
  assert.ok(requests.at(-1).options.signal instanceof AbortSignal);
});
test('reset invalidates earlier previews for the same session and binds challenges to their session', async () => {
  const first=await (await GET(request('GET'))).json();
  const second=await (await GET(request('GET'))).json();
  assert.equal((await POST(request('POST',{challenge:first.challenge,password:'correct',phrase:'Kompletter Reset'}))).status,409);
  nonce='other-session';
  assert.equal((await POST(request('POST',{challenge:second.challenge,password:'correct',phrase:'Kompletter Reset'}))).status,409);
  assert.equal(requests.filter(item=>item.options.method==='POST').length,0);
});
test('expired challenges cannot execute any backend reset', async () => {
  const originalNow=Date.now;
  const preview=await (await GET(request('GET'))).json();
  try {
    const now=originalNow(); Date.now=()=>now+120001;
    assert.equal((await POST(request('POST',{challenge:preview.challenge,password:'correct',phrase:'Kompletter Reset'}))).status,409);
    assert.equal(requests.filter(item=>item.options.method==='POST').length,0);
  } finally { Date.now=originalNow; }
});
test('body limit applies without content-length and non-JSON confirmations are rejected', async () => {
  const url='https://tsa.noizycologne.de/api/admin/factory-reset';
  const large=new Request(url,{method:'POST',headers:{origin:'https://tsa.noizycologne.de','content-type':'application/json'},body:new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode('x'.repeat(2049)));controller.close();}}),duplex:'half'});
  assert.equal(large.headers.get('content-length'),null);
  assert.equal((await POST(large)).status,400);
  const plain=new Request(url,{method:'POST',headers:{origin:'https://tsa.noizycologne.de','content-type':'text/plain'},body:'{}'});
  assert.equal((await POST(plain)).status,415); assert.equal(requests.length,0);
});
test('invalid internal endpoints and missing backend revisions fail before granting a challenge', async () => {
  for(const url of ['https://evil.example','http://api:8080/other','http://user:pass@api:8080','http://api:8080/?query=1','not a URL']) {
    process.env.NOIZY_INTERNAL_API_URL=url;
    assert.equal((await GET(request('GET'))).status,503);
  }
  assert.equal(requests.length,0);
  process.env.NOIZY_INTERNAL_API_URL='http://api:8080'; revision='missing';
  assert.equal((await GET(request('GET'))).status,502);
});
test('backend transport failure after POST is explicitly unknown instead of claiming no change', async () => {
  const {challenge}=await (await GET(request('GET'))).json();
  fetchOverride=async()=>{throw new TypeError('fixture transport lost');};
  const response=await POST(request('POST',{challenge,password:'correct',phrase:'Kompletter Reset'}));
  assert.equal(response.status,503); const data=await response.json();
  assert.equal(data.outcomeUnknown,true); assert.match(data.error,/Datenbank kann bereits/);
});
test('concurrent previews cannot leave two valid challenges for the same session', async () => {
  const responses=await Promise.all([GET(request('GET')),GET(request('GET'))]);
  assert.deepEqual(responses.map(response=>response.status).sort(),[200,409]);
});
test('preview capacity is bounded before making extra backend requests', async () => {
  const originalNow=Date.now;
  try {
    const future=originalNow()+240000; Date.now=()=>future;
    for(let index=0;index<64;index++) {
      nonce=`bounded-session-${index}`; assert.equal((await GET(request('GET'))).status,200);
    }
    nonce='one-too-many'; assert.equal((await GET(request('GET'))).status,503); assert.equal(requests.length,64);
    Date.now=()=>future+120001; assert.equal((await GET(request('GET'))).status,200);
  } finally { Date.now=originalNow; }
});
