import dns from 'node:dns/promises';
import { isIP } from 'node:net';
import { createHmac, timingSafeEqual } from 'node:crypto';

export const BOT = 'NoizyCologneDiscovery/1.0';
// A bounded, canonicalized GET target. Every safety check below remains unchanged.
export const MAX_URL_LENGTH = 4096;
// Generic tracking/telemetry query keys, not venue- or club-specific selectors.
const TRACKING_KEYS = new Set(['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
  'fbclid', 'gclid', 'gclsrc', 'dclid', 'msclkid', 'mc_cid', 'mc_eid', '_ga', '_gl', 'igshid', 'yclid']);
// Platform-level CDN/telemetry hosts. No venue names, paths or content selectors.
const ASSET_HOSTS = ['wixstatic.com', 'parastorage.com', 'wixpress.com'];
const TELEMETRY_HOSTS = ['sentry-cdn.com', 'frog.wix.com', 'panorama.wixapps.net', 'google-analytics.com',
  'googletagmanager.com', 'googletagservices.com', 'doubleclick.net', 'hotjar.com', 'segment.com', 'segment.io',
  'mixpanel.com', 'amplitude.com'];
const hostIn = (host, suffixes) => suffixes.some(suffix => host === suffix || host.endsWith('.' + suffix));

/** Drop tracking/empty/duplicate query pairs. Same origin, path and method; no new request. */
export function canonicalUrl(value) {
  const url = new URL(value);
  const raw = url.search.replace(/^\?/, '');
  if (!raw) return url.href;
  const seen = new Set();
  const kept = [];
  for (const pair of raw.split('&')) {
    if (!pair) continue;
    const eq = pair.indexOf('=');
    const rawKey = eq < 0 ? pair : pair.slice(0, eq);
    let key;
    try { key = decodeURIComponent(rawKey.replace(/\+/g, ' ')).trim().toLowerCase(); }
    catch { key = rawKey.trim().toLowerCase(); }
    if (!key || TRACKING_KEYS.has(key) || seen.has(key)) continue;
    seen.add(key);
    kept.push(pair);
  }
  url.search = kept.join('&');
  return url.href;
}
export function publicUrl(value) {
  if (typeof value !== 'string' || value.length > MAX_URL_LENGTH) throw new Error('UNSAFE_URL');
  const url = new URL(canonicalUrl(value));
  if (url.href.length > MAX_URL_LENGTH || url.protocol !== 'https:' || url.username || url.password || url.hash || url.port ||
      !url.hostname.includes('.') || url.hostname.endsWith('.') || isIP(url.hostname) || /[\[\]:]/.test(url.hostname)) throw new Error('UNSAFE_URL');
  return url;
}
/** Egress priority for subrequests; 'background' never consumes a protected tunnel. */
export function egressTier(value, primaryHost = '') {
  let host;
  try { host = new URL(canonicalUrl(value)).hostname.toLowerCase(); }
  catch { return 'background'; }
  if (hostIn(host, TELEMETRY_HOSTS)) return 'background';
  if (primaryHost && host === primaryHost.toLowerCase()) return 'primary';
  if (hostIn(host, ASSET_HOSTS)) return 'asset';
  return 'normal';
}
export function publicAddress(address) {
  const family = isIP(address);
  if (family === 4) {
    const [a, b] = address.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && [0, 168, 2, 88].includes(b)) || (a === 198 && [18, 19, 51].includes(b)) || (a === 203 && b === 0));
  }
  if (family !== 6 || address.includes('.')) return false;
  const [first, second = '0'] = address.split(':');
  const a = Number.parseInt(first, 16), b = Number.parseInt(second || '0', 16);
  return (a & 0xe000) === 0x2000 && !(a === 0x2001 && b < 0x2000) && a !== 0x2002 && a !== 0x3fff;
}
export async function resolvePublic(host, lookup = dns.lookup) {
  let timer;
  const addresses = await Promise.race([lookup(host, { all: true, verbatim: true }),
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('DNS_TIMEOUT')), 5000); })]).finally(() => clearTimeout(timer));
  if (!addresses.length || addresses.some(item => !publicAddress(item.address))) throw new Error('UNSAFE_DNS');
  return addresses;
}
export function secret(value) {
  if (typeof value !== 'string' || value.length < 32) throw new Error('A browser worker token of at least 32 characters is required');
  return value;
}
export function proxySecret(token) { return createHmac('sha256', token).update('noizy-browser-egress-v1').digest('hex'); }
export function authorized(actual, expected) {
  const a = Buffer.from(actual ?? ''), b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}
export async function readJson(request, limit = 4096) {
  let size = 0; const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) throw new Error('RESPONSE_TOO_LARGE');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
export function send(response, code, data) {
  response.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(data));
}

// Product-specific groups override wildcard groups; a crawl-delay is conservatively blocked.
export function robotsAllowed(text, path) {
  const groups = []; let group;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.split('#', 1)[0].trim(), colon = line.indexOf(':');
    if (colon < 0) continue;
    const key = line.slice(0, colon).trim().toLowerCase(), value = line.slice(colon + 1).trim();
    if (key === 'user-agent') {
      if (!group || group.started) { group = { agents: [], rules: [], started: false, delay: false }; groups.push(group); }
      group.agents.push(value.toLowerCase());
    } else if (group && ['allow', 'disallow', 'crawl-delay'].includes(key)) {
      group.started = true;
      if (key === 'crawl-delay') group.delay = true;
      else if (value) group.rules.push({ pattern: value, allow: key === 'allow' });
    }
  }
  const specific = groups.some(item => item.agents.includes('noizycolognediscovery'));
  let longest = -1, allowed = true;
  for (const item of groups.filter(item => item.agents.includes(specific ? 'noizycolognediscovery' : '*'))) {
    if (item.delay) return false;
    for (const rule of item.rules) {
      if (!globMatches(rule.pattern, path)) continue;
      const length = Buffer.byteLength(rule.pattern.replace(/\*/g, '').replace(/\$$/, ''));
      if (length > longest) { longest = length; allowed = rule.allow; }
      else if (length === longest && rule.allow) allowed = true;
    }
  }
  return allowed;
}

export function globMatches(pattern, path) {
  const end = pattern.endsWith('$');
  if (end) pattern = pattern.slice(0, -1);
  let p = 0, t = 0, star = -1, retry = 0;
  while (t < path.length) {
    if (p === pattern.length && !end) return true;
    if (pattern[p] === '*') { star = p++; retry = t; }
    else if (p < pattern.length && pattern[p] === path[t]) { p++; t++; }
    else if (star >= 0) { p = star + 1; t = ++retry; }
    else return false;
  }
  while (pattern[p] === '*') p++;
  return p === pattern.length;
}
