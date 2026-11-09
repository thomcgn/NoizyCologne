import assert from 'node:assert/strict';
import {createHash, createHmac, randomBytes} from 'node:crypto';

/** Test-only registered sessions; never accepts a remote backend or a production origin. */
export async function visualSession(origin) {
  const target = new URL(origin);
  assert.ok(['localhost', '127.0.0.1'].includes(target.hostname), 'UI preview must stay on loopback');
  const secret = process.env.DISCOVERY_VISUAL_SECRET;
  const key = process.env.DISCOVERY_VISUAL_AUTH_KEY;
  const username = process.env.DISCOVERY_VISUAL_USER;
  const hash = process.env.DISCOVERY_VISUAL_PASSWORD_HASH;
  const base = new URL(process.env.DISCOVERY_VISUAL_STORE_URL ?? 'http://127.0.0.1:9');
  assert.ok(base.protocol==='http:' && ['localhost', '127.0.0.1'].includes(base.hostname) && !base.username && !base.password && base.pathname==='/' && !base.search && !base.hash, 'Disposable auth store must stay on loopback');
  assert.ok(secret?.length >= 32 && key?.length >= 32 && username && hash, 'Run through visual-auth.mjs with AUTH_VISUAL_SCRIPT or supply all disposable fixture settings');
  const digest = value => createHash('sha256').update(value).digest('hex');
  const now = Math.floor(Date.now()/1000);
  const payload = {sub: username, iat: now, exp: now+28800, nonce: randomBytes(16).toString('hex'), generation: digest(JSON.stringify([username, hash, secret]))};
  const response = await fetch(new URL('/internal/auth/register', base), {method: 'POST', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(2000), headers: {'Content-Type': 'application/json', 'X-Noizy-Auth-Key': key}, body: JSON.stringify({nonceHash: digest(payload.nonce), generation: payload.generation, expiresAt: payload.exp})});
  assert.ok(response.ok && (await response.json()).valid===true, 'Disposable session registration failed');
  const encoded = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signingKey = createHmac('sha256', secret).update('noizy-session-v2').digest();
  return {token: `${encoded}.${createHmac('sha256', signingKey).update(encoded).digest('base64url')}`, contextOptions: {ignoreHTTPSErrors: true, extraHTTPHeaders: {'X-Noizy-Auth-Key': key, 'X-Noizy-Client-IP': '192.0.2.10'}}};
}
