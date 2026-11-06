import { render } from './render.mjs';
import { secret, proxySecret } from './security.mjs';
const token = proxySecret(secret(process.env.BROWSER_WORKER_TOKEN));
const gateway = 'http://browser-gateway:3128';
const robots = new Map();
const permit = async url => {
  const key = new URL(url).origin + new URL(url).pathname + new URL(url).search;
  if (!robots.has(key)) robots.set(key, fetch(`${gateway}/robots`, { method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }), signal: AbortSignal.timeout(7000) }).then(async response => {
      if (!response.ok) return false;
      return (await response.json()).allowed === true;
    }));
  return robots.get(key);
};
process.once('message', async job => {
  try {
    const result = await render(job.url, { proxy: { server: gateway, username: 'discovery', password: token }, permit,
      timeout: Math.max(3000, Math.min(Number(process.env.BROWSER_TIMEOUT_MS || 20000), 30000)) });
    process.send(result, () => process.exit(0));
  } catch { process.send({ status: 'BROWSER_UNAVAILABLE', pages: [], warnings: ['BROWSER_UNAVAILABLE'], complete: false }, () => process.exit(1)); }
});
