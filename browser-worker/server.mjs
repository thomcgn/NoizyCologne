import http from 'node:http';
import { fork } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { secret, authorized, readJson, publicUrl, send } from './security.mjs';
const children = new Set();

export function runChild(job, { timeout = 40000, forkProcess = fork } = {}) {
  return new Promise(resolve => {
    const child = forkProcess(new URL('./child.mjs', import.meta.url), [], { detached: true, stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
    children.add(child);
    let received = false, timedOut = false;
    let result = { status: 'BROWSER_UNAVAILABLE', pages: [], warnings: ['BROWSER_UNAVAILABLE'], complete: false };
    const kill = () => { if (child.pid) try { process.kill(-child.pid, 'SIGKILL'); } catch { /* Already reaped. */ } };
    const timer = setTimeout(() => {
      timedOut = true;
      result = { status: 'BROWSER_TIMEOUT', pages: [], warnings: ['BROWSER_TIMEOUT'], complete: false };
      kill();
      // Do not wait indefinitely for a stuck OS child to emit its exit event.
      resolve({ ...result, restartWorker: true });
    }, timeout);
    child.on('message', message => { received = true; result = message; });
    child.once('error', () => { clearTimeout(timer); kill(); children.delete(child); resolve({ ...result, restartWorker: true }); });
    child.once('exit', code => { clearTimeout(timer); kill(); children.delete(child); resolve({ ...result, restartWorker: timedOut || !received || code !== 0 }); });
    child.send(job);
  });
}
export function createWorker(token, runner = runChild, retire = () => process.exit(1)) {
  let busy = false, retiring = false;
  return http.createServer({ requestTimeout: 5000, headersTimeout: 5000, maxHeaderSize: 8192 }, async (request, response) => {
    if (request.method === 'GET' && request.url === '/health') { send(response, retiring ? 503 : 200, { ok: !retiring }); return; }
    if (request.method !== 'POST' || request.url !== '/render' || !authorized(request.headers.authorization, `Bearer ${token}`)) { send(response, 403, { status: 'BLOCKED' }); return; }
    if (busy || retiring) { send(response, 429, { status: 'BROWSER_BUSY' }); return; }
    busy = true;
    try {
      const job = await readJson(request); publicUrl(job.url);
      const { restartWorker, ...result } = await runner({ url: job.url });
      retiring = restartWorker === true;
      if (Buffer.byteLength(JSON.stringify(result)) > 4 * 1024 * 1024) send(response, 502, { status: 'RESPONSE_TOO_LARGE' });
      else send(response, 200, result);
    } catch { if (!response.writableEnded) send(response, 400, { status: 'BLOCKED' }); }
    finally {
      busy = false;
      if (retiring) {
        // Playwright launches Chromium in its own process group. A hard-killed
        // child cannot close that group reliably; retire the dedicated container
        // so its PID namespace is destroyed before Docker restarts the worker.
        let done = false;
        const finish = () => { if (!done) { done = true; clearTimeout(timer); retire(); } };
        const timer = setTimeout(finish, 250); timer.unref();
        if (response.writableFinished || response.destroyed) finish();
        else { response.once('finish', finish); response.once('close', finish); }
      }
    }
  });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  for (const signal of ['SIGTERM','SIGINT']) process.once(signal, () => {
    for (const child of children) if (child.pid) try { process.kill(-child.pid, 'SIGKILL'); } catch { /* Already reaped. */ }
    process.exit(0);
  });
  const token = secret(process.env.BROWSER_WORKER_TOKEN);
  try { const {verifySandbox}=await import('./sandbox-check.mjs'); await verifySandbox(); }
  catch { console.error('BROWSER_SANDBOX_FAILED: Chromium startup failed with sandbox enabled; inspect container limits, seccomp, user namespaces and host AppArmor'); process.exit(1); }
  createWorker(token).listen(3000, '0.0.0.0');
}
