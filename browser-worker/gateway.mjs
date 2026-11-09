import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { pathToFileURL } from 'node:url';
import { BOT, publicUrl, resolvePublic, secret, proxySecret, authorized, readJson, send, robotsAllowed } from './security.mjs';

// Resolver results are used by the socket itself; no second, unchecked DNS lookup.
export async function readPublic(url, { lookup, get = https.get } = {}) {
  const target = publicUrl(url);
  const [address] = await resolvePublic(target.hostname, lookup);
  return new Promise((resolve, reject) => {
    const request = get(target, {
      agent: false, headers: { 'User-Agent': BOT, Accept: 'text/plain' },
      lookup: (_host, options, callback) => options.all ? callback(null, [address]) : callback(null, address.address, address.family),
    }, response => {
      const chunks = []; let length = 0;
      response.on('data', chunk => {
        length += chunk.length;
        if (length > 262144) { request.destroy(new Error('RESPONSE_TOO_LARGE')); return; }
        chunks.push(chunk);
      });
      response.on('error', reject);
      response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    const timer = setTimeout(() => request.destroy(new Error('FETCH_TIMEOUT')), 6000);
    request.on('error', reject); request.on('close', () => clearTimeout(timer));
  });
}

export function createGateway(token, { lookup, connect = net.createConnection, read = readPublic,
  workerHost = 'browser-worker', workerPort = 3000 } = {}) {
  const proxyToken = proxySecret(token);
  const cache = new Map();
  let active = 0, interval = Date.now(), bytes = 0, connections = 0, reported = 0;
  const diagnose = code => {if(reported++<24)console.warn(code);};
  const quota = amount => {
    if (Date.now() - interval > 60000) { interval = Date.now(); bytes = 0; connections = 0; reported = 0; }
    bytes += amount;
    return bytes <= 64 * 1024 * 1024;
  };
  const server = http.createServer({ requestTimeout: 35000, headersTimeout: 5000, maxHeaderSize: 8192 }, async (request, response) => {
    if (request.method === 'GET' && request.url === '/health') { send(response, 200, { ok: true }); return; }
    if (request.method === 'GET' && request.url === '/ready') {
      try {
        const worker = await fetch(`http://${workerHost}:${workerPort}/health`, {signal:AbortSignal.timeout(2500)});
        const result = await worker.json();
        if (!worker.ok || result.ok !== true) throw new Error('BROWSER_WORKER_UNAVAILABLE');
        send(response,200,{ok:true});
      } catch { send(response,503,{ok:false,status:'BROWSER_WORKER_UNAVAILABLE'}); }
      return;
    }
    try {
      if (request.method === 'POST' && request.url === '/robots' && authorized(request.headers.authorization, `Bearer ${proxyToken}`)) {
        const url = publicUrl((await readJson(request)).url);
        let entry = cache.get(url.origin);
        if (!entry || entry.expires < Date.now()) {
          const result = await read(new URL('/robots.txt', url).href, { lookup });
          if (result.status !== 200 && result.status !== 404) throw new Error('ROBOTS_UNAVAILABLE');
          entry = { body: result.status === 404 ? '' : result.body, expires: Date.now() + 60000 };
          if (cache.size >= 128) cache.clear();
          cache.set(url.origin, entry);
        }
        send(response, 200, { allowed: robotsAllowed(entry.body, url.pathname + url.search) }); return;
      }
      if (request.method !== 'POST' || request.url !== '/render' || !authorized(request.headers.authorization, `Bearer ${token}`)) {
        send(response, 403, { status: 'BLOCKED' }); return;
      }
      const body = await readJson(request); publicUrl(body.url);
      const relay = http.request({ hostname: workerHost, port: workerPort, path: '/render', method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } }, result => {
        let size = 0; const chunks = [];
        result.on('data', chunk => { size += chunk.length; if (size > 4 * 1024 * 1024) relay.destroy(new Error('RESPONSE_TOO_LARGE')); else chunks.push(chunk); });
        result.on('end', () => { if (!response.writableEnded) { response.writeHead(result.statusCode, { 'Content-Type': 'application/json' }); response.end(Buffer.concat(chunks)); } });
        result.on('error', () => { if (!response.writableEnded) send(response, 502, { status: 'BROWSER_UNAVAILABLE' }); });
      });
      const timer = setTimeout(() => relay.destroy(new Error('BROWSER_TIMEOUT')), 35000);
      relay.on('close', () => clearTimeout(timer));
      relay.on('error', () => { if (!response.writableEnded) send(response, 502, { status: 'BROWSER_UNAVAILABLE' }); });
      response.on('close', () => relay.destroy());
      relay.end(JSON.stringify({ url: body.url }));
    } catch { if (!response.writableEnded) send(response, 403, { status: 'BLOCKED' }); }
  });
  server.on('connect', async (request, client, head) => {
    let upstream, timer; let counted = false;
    const reject = (code='BROWSER_PROXY_NETWORK_ERROR') => { diagnose(code); client.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); upstream?.destroy(); };
    client.on('error', () => upstream?.destroy());
    client.on('close', () => { clearTimeout(timer); upstream?.destroy(); if (counted) { active--; counted = false; } });
    try {
      const expected = `Basic ${Buffer.from(`discovery:${proxyToken}`).toString('base64')}`;
      if (!authorized(request.headers['proxy-authorization'], expected)) {reject('BROWSER_PROXY_AUTH_FAILED');return;}
      if (active >= 32) {reject('BROWSER_PROXY_CONNECTION_LIMIT');return;}
      if (!quota(0) || ++connections > 200) {reject('BROWSER_PROXY_RATE_LIMIT');return;}
      if (!request.url.endsWith(':443')) { reject('BROWSER_PROXY_PORT_BLOCKED'); return; }
      const host = request.url.slice(0, -4); publicUrl(`https://${host}/`);
      active++; counted = true;
      const [address] = await resolvePublic(host, lookup);
      if (client.destroyed) return;
      upstream = connect({ host: address.address, port: 443, family: address.family });
      timer = setTimeout(() => { client.destroy(); upstream.destroy(); }, 25000);
      let transferred = 0;
      const account = chunk => {
        transferred += chunk.length;
        if (transferred > 12 * 1024 * 1024 || !quota(chunk.length)) { client.destroy(); upstream.destroy(); }
      };
      upstream.on('error', () => reject());
      upstream.on('connect', () => { client.write('HTTP/1.1 200 Connection Established\r\n\r\n'); if (head.length) upstream.write(head); client.pipe(upstream); upstream.pipe(client); });
      client.on('data', account); upstream.on('data', account);
    } catch(error) { reject(['UNSAFE_URL','UNSAFE_DNS','DNS_TIMEOUT'].includes(error.message)?error.message:'BROWSER_PROXY_NETWORK_ERROR'); }
  });
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createGateway(secret(process.env.BROWSER_WORKER_TOKEN)).listen(3128, '0.0.0.0');
}
