import { test } from 'node:test';
import assert from 'node:assert/strict';
import { publicUrl, publicAddress, resolvePublic, proxySecret, authorized, robotsAllowed, globMatches } from '../security.mjs';
test('only public HTTPS URLs without credentials or special ports', () => {
  for (const url of ['http://club.example','file:///etc/passwd','https://127.0.0.1','https://2130706433','https://0x7f000001','https://[::1]','https://user:pass@club.example','https://club.example:8080','https://localhost/','https://club.example./']) assert.throws(() => publicUrl(url), undefined, url);
  assert.equal(publicUrl('https://club.example/events?page=2').hostname, 'club.example');
});
test('private, mapped, transition, documentation, metadata and mixed DNS answers are blocked', async () => {
  for (const ip of ['0.0.0.0','10.0.0.1','127.0.0.1','100.64.0.1','169.254.169.254','172.16.0.1','192.168.0.1','192.0.2.1','198.18.0.1','198.51.100.1','203.0.113.1','224.0.0.1','::1','fc00::1','fe80::1','::ffff:127.0.0.1','2001:db8::1','2002::1','3fff::1']) assert.equal(publicAddress(ip), false, ip);
  for (const ip of ['8.8.8.8','93.184.216.34','2606:4700::1111','2001:4860:4860::8888']) assert.equal(publicAddress(ip), true, ip);
  await assert.rejects(resolvePublic('club.example', async () => [{address:'8.8.8.8',family:4},{address:'127.0.0.1',family:4}]), /UNSAFE_DNS/);
});
test('derived proxy credential cannot authorize the API', () => {
  const token = 'test-token-for-isolated-fixtures-only-12345';
  assert.equal(authorized(`Bearer ${token}`, `Bearer ${token}`), true);
  assert.equal(authorized(`Bearer ${proxySecret(token)}`, `Bearer ${token}`), false);
  assert.equal(authorized(undefined, token), false);
});
test('robots specific groups, longest match, glob anchors and crawl-delay', () => {
  const text = 'User-agent: *\nDisallow: /\nUser-agent: NoizyCologneDiscovery\nDisallow: /private\nAllow: /private/public\nDisallow: /*.ics$';
  for (const [path, allowed] of [['/events',true],['/private',false],['/private/public',true],['/calendar.ics',false],['/calendar.ics?page=1',true]]) assert.equal(robotsAllowed(text,path),allowed,path);
  assert.equal(robotsAllowed('User-agent: *\nCrawl-delay: 10','/events'),false);
  assert.equal(globMatches('/x.[a]+','/x.[a]+/more'),true);
  assert.equal(globMatches('/*a*a*a*a*a*b$','/'+'a'.repeat(2048)),false);
});
