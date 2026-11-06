// Run only as the main process of a disposable Docker PID namespace.
import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createWorker, runChild } from '../../server.mjs';
const token = 'offline-lifecycle-fixture-token-123456789';
let result;
setTimeout(() => { throw new Error('Worker did not retire after its watchdog'); }, 6000);
const worker = createWorker(token, async job => {
  result = await runChild(job, { timeout: 1000,
    forkProcess: (_url, args, options) => fork(new URL('./orphan-child.mjs', import.meta.url), args, options) });
  return result;
}, () => {
  assert.equal(result.status, 'BROWSER_TIMEOUT');
  assert.equal(result.restartWorker, true);
  const pid = Number(readFileSync('/tmp/noizy-watchdog-descendant', 'utf8'));
  assert.ok(pid > 1 && pid !== process.pid);
  // Killing the original child group left this detached process alive. Exiting
  // this container's main process destroys its namespace and reaps that process.
  process.kill(pid, 0);
  console.log('PASS: watchdog retires the isolated worker with its detached descendant');
  process.exit(0);
});
worker.listen(0, '127.0.0.1', () => {
  fetch(`http://127.0.0.1:${worker.address().port}/render`, { method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ url: 'https://fixture.example/' }) }).catch(() => {});
});
