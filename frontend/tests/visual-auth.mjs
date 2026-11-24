// Real Next/Chromium auth flow with a disposable loopback-only auth-store fixture.
// Caddy header derivation and PostgreSQL persistence have separate integration tests.
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import {spawn, spawnSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import bcrypt from 'bcryptjs';
import {controlCenterFixture, mockControlCenterApi} from './fixtures/controlCenter.mjs';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const root = fileURLToPath(new URL('..', import.meta.url));
const out = resolve(process.env.AUTH_VISUAL_OUTPUT ?? '.quality/auth-proxy');
const work = await mkdtemp(resolve(tmpdir(), 'noizy-auth-browser-'));
const serviceKey = 'disposable-auth-browser-service-key-no-production-value';
const username = 'auth-browser-fixture';
const password = 'disposable-auth-browser-password';
const sessions = new Map();
const attempts = new Map();
let unavailable = false;
let child;
let browser;
let origin;
let childOutput = '';
const store = http.createServer(async (request, response) => {
  response.setHeader('Content-Type', 'application/json');
  response.setHeader('Cache-Control', 'no-store');
  if (request.headers['x-noizy-auth-key'] !== serviceKey) {response.writeHead(403); response.end('{}'); return;}
  if (unavailable) {response.writeHead(503); response.end('{}'); return;}
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {bytes += chunk.length; if (bytes > 8192) {response.writeHead(413); response.end('{}'); return;} chunks.push(chunk);}
  let body;
  try {body = JSON.parse(Buffer.concat(chunks).toString());}
  catch {response.writeHead(400); response.end('{}'); return;}
  let result;
  switch (request.url) {
    case '/internal/auth/register': sessions.set(body.nonceHash, {...body, revoked: false}); result = {valid: true}; break;
    case '/internal/auth/validate': {const row = sessions.get(body.nonceHash); result = {valid: Boolean(row && !row.revoked && row.generation === body.generation && row.expiresAt > Date.now()/1000)}; break;}
    case '/internal/auth/revoke': if (sessions.has(body.nonceHash)) sessions.get(body.nonceHash).revoked = true; result = {valid: false}; break;
    case '/internal/auth/attempt': {const count = attempts.get(body.addressHash) ?? 0; attempts.set(body.addressHash, count+1); result = {allowed: count < 5, retryAfter: count < 5 ? 0 : 900}; break;}
    case '/internal/auth/clear': attempts.delete(body.addressHash); result = {valid: true}; break;
    default: response.writeHead(404); response.end('{}'); return;
  }
  response.end(JSON.stringify(result));
});
function port(server) {const address = server.address(); assert.ok(address && typeof address === 'object'); return address.port;}
async function stopNext() {
  if (!child || child.exitCode !== null) return;
  const current = child;
  current.kill('SIGTERM');
  const exited = once(current, 'exit');
  const timeout = setTimeout(() => current.kill('SIGKILL'), 5000);
  try {await exited;} finally {clearTimeout(timeout); child = undefined;}
}
try {
  await mkdir(out, {recursive: true});
  const cert = resolve(work, 'cert.pem'), key = resolve(work, 'key.pem');
  const generated = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', key, '-out', cert], {stdio: 'ignore'});
  assert.equal(generated.status, 0, 'Temporary local TLS certificate generation failed');
  store.listen(0, '127.0.0.1'); await once(store, 'listening');
  const reservation = net.createServer().listen(0, '127.0.0.1'); await once(reservation, 'listening');
  const nextPort = port(reservation); await new Promise(resolve => reservation.close(resolve));
  origin = `https://127.0.0.1:${nextPort}`;
  const ca = await readFile(cert);
  const passwordHash = bcrypt.hashSync(password, 4);
  const environment = {...process.env, TEAM_USER: username, TEAM_PASSWORD_HASH: passwordHash, TEAM_SESSION_SECRET: 'disposable-auth-browser-signing-secret-no-production-value', AUTH_SERVICE_SECRET: serviceKey, NOIZY_PUBLIC_ORIGIN: origin, NOIZY_INTERNAL_API_URL: `http://127.0.0.1:${port(store)}`, LOCAL_BACKEND_URL: 'http://127.0.0.1:9'};
  async function startNext() {
    child = spawn(process.execPath, [resolve(root, 'node_modules/next/dist/bin/next'), 'dev', '--hostname', '127.0.0.1', '--port', String(nextPort), '--experimental-https', '--experimental-https-key', key, '--experimental-https-cert', cert], {cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe']});
    child.stdout.on('data', chunk => {childOutput = (childOutput + chunk).slice(-16000);});
    child.stderr.on('data', chunk => {childOutput = (childOutput + chunk).slice(-16000);});
    for (let index=0; index<120; index++) {
      if (child.exitCode !== null) throw new Error('Disposable Next server exited before readiness');
      const ready = await new Promise(resolve => {const request = https.get(origin+'/login', {ca, timeout: 1000}, response => {response.resume(); resolve(response.statusCode===200);}); request.on('error', () => resolve(false)); request.on('timeout', () => request.destroy());});
      if (ready) return;
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('Disposable Next server readiness timed out');
  }
  await startNext();
  if (process.env.AUTH_VISUAL_SCRIPT) {
    const name = process.env.AUTH_VISUAL_SCRIPT;
    assert.ok(['visual-control-center.mjs', 'visual-discovery.mjs', 'visual-discovery-confirmation.mjs', 'visual-discovery-geography.mjs', 'visual-preferences.mjs', 'visual-special-sources.mjs', 'visual-design-tokens.mjs'].includes(name), 'Unknown existing visual fixture');
    const fixture = spawn(process.execPath, [resolve(root, 'tests', name)], {cwd: process.cwd(), stdio: 'inherit', env: {...process.env, DISCOVERY_VISUAL_URL: origin, DISCOVERY_VISUAL_SECRET: environment.TEAM_SESSION_SECRET, DISCOVERY_VISUAL_USER: username, DISCOVERY_VISUAL_PASSWORD_HASH: passwordHash, DISCOVERY_VISUAL_AUTH_KEY: serviceKey, DISCOVERY_VISUAL_STORE_URL: environment.NOIZY_INTERNAL_API_URL}});
    const [code] = await once(fixture, 'exit');
    assert.equal(code, 0, 'Existing visual fixture failed against registered sessions');
  }
  if (!process.env.AUTH_VISUAL_SCRIPT) {
  browser = await chromium.launch({executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable', headless: true});
  const results = [];
  let restarted = false;
  for (const theme of ['graphite', 'midnight', 'light']) for (const width of [1440, 390]) {
    attempts.clear();
    const proxyHeaders = {'X-Noizy-Auth-Key': serviceKey, 'X-Noizy-Client-IP': '192.0.2.10'};
    const context = await browser.newContext({viewport: {width, height: 1000}, isMobile: width===390, hasTouch: width===390, ignoreHTTPSErrors: true, extraHTTPHeaders: proxyHeaders});
    await context.addInitScript(theme => localStorage.setItem('noizy-theme', theme), theme);
    await mockControlCenterApi(context, controlCenterFixture());
    const page = await context.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin+'/login');
    await page.getByLabel('Benutzername', {exact: true}).fill(username);
    await page.getByLabel('Passwort', {exact: true}).fill(password);
    await page.getByRole('button', {name: 'Anmelden', exact: true}).click();
    await page.getByRole('heading', {name: 'Dein Arbeitsbereich', exact: true}).waitFor();
    const cookie = (await context.cookies()).find(item => item.name==='noizy_team_session');
    assert.ok(cookie?.secure && cookie.httpOnly && cookie.sameSite==='Lax');
    unavailable = true;
    const failedLogout = await context.request.post(origin+'/api/auth/logout', {headers: {Origin: origin, 'Sec-Fetch-Site': 'same-origin'}});
    assert.equal(failedLogout.status(), 503);
    assert.equal((await context.cookies()).find(item => item.name===cookie.name)?.value, cookie.value);
    unavailable = false;
    const logout = await context.request.post(origin+'/api/auth/logout', {headers: {Origin: origin, 'Sec-Fetch-Site': 'same-origin'}});
    assert.equal(logout.status(), 200);
    assert.equal(logout.headers()['cache-control'], 'no-store');
    if (!restarted) {await stopNext(); await startNext(); restarted = true;}
    const replay = await browser.newContext({ignoreHTTPSErrors: true, extraHTTPHeaders: proxyHeaders});
    await replay.addCookies([cookie]);
    const rejected = await replay.request.get(origin+'/api/auth/verify');
    assert.equal(rejected.status(), 401);
    await replay.close();
    await page.goto(origin+'/login');
    unavailable = true;
    await page.getByLabel('Benutzername', {exact: true}).fill(username);
    await page.getByLabel('Passwort', {exact: true}).fill(password);
    await page.getByRole('button', {name: 'Anmelden', exact: true}).click();
    await page.getByRole('alert').filter({hasText: 'derzeit nicht verfügbar'}).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: `${out}/${theme}-${width}-store-unavailable.png`, fullPage: true});
    unavailable = false;
    assert.deepEqual(errors, []);
    results.push({theme, width, login: true, failedLogoutRetainsCookie: true, replayRejected: true, outageReported: true});
    await context.close();
  }
  await writeFile(resolve(out, 'results.json'), JSON.stringify({restartReplayVerified: restarted, scenarios: results}, null, 2)+'\n');
  console.log(`PASS: ${results.length} real Next/Chromium auth scenarios; frontend restart rejects copied revoked cookie; ${out}`);
  }
} finally {
  unavailable = false;
  if (browser) await browser.close();
  await stopNext();
  store.closeAllConnections();
  await new Promise(resolve => store.close(resolve));
  await rm(work, {recursive: true, force: true});
  // Captured Next output is kept local for diagnosis; contains no production configuration.
  if (childOutput) await writeFile(resolve(out, 'next-fixture.log'), childOutput);
}
