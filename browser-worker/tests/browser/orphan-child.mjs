// Trusted offline fixture: reproduce Playwright's detached browser process group.
import { setInterval } from 'node:timers';
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
process.once('message', () => {
  const detached = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' });
  detached.unref();
  writeFileSync('/tmp/noizy-watchdog-descendant', String(detached.pid));
  setInterval(() => {}, 1000);
});
