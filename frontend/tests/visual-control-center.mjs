import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture, mockControlCenterApi} from './fixtures/controlCenter.mjs';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const origin = process.env.DISCOVERY_VISUAL_URL ?? 'http://127.0.0.1:3107';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(origin).hostname));
const secret = process.env.DISCOVERY_VISUAL_SECRET;
assert.ok(secret, 'Disposable local preview secret required');
const phase = Number(process.env.CC_PHASE ?? 1);
const out = resolve(process.env.CC_OUTPUT ?? `.quality/control-center/phase-${phase}`);
await mkdir(out, {recursive: true});
const now = Math.floor(Date.now() / 1000);
const payload = Buffer.from(JSON.stringify({sub: 'control-center-fixture', iat: now, exp: now + 3600, nonce: 'local-ui-only'})).toString('base64url');
const token = `${payload}.${createHmac('sha256', secret).update(payload).digest('base64url')}`;
const browser = await chromium.launch({executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable', headless: true});
const results = [];
try {
  for (const [theme, density, width, state] of [
    ['graphite', 'compact', 1440, 'populated'], ['midnight', 'comfortable', 1440, 'populated'], ['light', 'compact', 1440, 'populated'],
    ['graphite', 'comfortable', 768, 'populated'], ['midnight', 'compact', 390, 'populated'], ['light', 'comfortable', 390, 'populated'],
    ['graphite', 'compact', 1440, 'empty'], ['light', 'compact', 1440, 'error'], ['midnight', 'compact', 320, 'populated'],
  ]) {
    const context = await browser.newContext({viewport: {width, height: 1000}, hasTouch: width < 1024, isMobile: width < 768});
    await context.addCookies([{name: 'noizy_team_session', value: token, url: origin}]);
    await context.addInitScript(({theme, density}) => {localStorage.setItem('noizy-theme', theme); localStorage.setItem('noizy-density', density);}, {theme, density});
    const errors = [];
    const calls = [];
    const data = controlCenterFixture(state);
    await mockControlCenterApi(context, data, {state, onRequest: request => calls.push(request)});
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${origin}/dashboard`);
    await page.getByRole('heading', {name: 'Dein Arbeitsbereich', exact: true}).waitFor();
    await page.waitForFunction(() => !document.querySelector('main')?.textContent.includes('Termine werden geladen'), undefined, {timeout: 15000});
    assert.equal(await page.locator('html').getAttribute('data-theme'), theme);
    assert.equal(await page.locator('html').getAttribute('data-density'), density);
    if (width >= 1024) {
      await page.getByRole('button', {name: 'Navigation einklappen', exact: true}).click();
      assert.equal(await page.getByRole('button', {name: 'Navigation erweitern', exact: true}).getAttribute('aria-expanded'), 'false');
      await page.getByRole('button', {name: 'Navigation erweitern', exact: true}).click();
    } else {
      await page.getByRole('button', {name: 'Menü', exact: true}).click();
      assert.equal(await page.getByRole('link', {name: 'Automatisierung', exact: true}).isVisible(), true);
      await page.getByRole('link', {name: 'Übersicht', exact: true}).focus();
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('button', {name: 'Menü', exact: true}).getAttribute('aria-expanded'), 'false');
    }
    const name = `${theme}-${density}-${width}-${state}`;
    await page.screenshot({path: `${out}/${name}.png`, fullPage: true});
    for (const route of ['/dashboard/vorschlaege', '/dashboard/partys', '/dashboard/djs', '/dashboard/locations', '/dashboard/kalender', '/dashboard/redaktion', '/dashboard/settings', '/dashboard/automatisierung']) {
      if (phase === 1 && !['/dashboard/settings', '/dashboard/automatisierung'].includes(route)) continue;
      await page.goto(`${origin}${route}`);
      await page.locator('main h1').waitFor();
      await page.waitForTimeout(180);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name} ${route}: horizontal overflow`);
    }
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: overflow`);
    assert.deepEqual(errors, [], `${name}: browser errors`);
    assert.ok(calls.every(call => call.method === 'GET'), `${name}: screenshot scenario attempted an API write`);
    results.push({theme, density, width, state, passed: true});
    await context.close();
  }
} finally {await browser.close();}
await writeFile(`${out}/results.json`, JSON.stringify(results, null, 2) + '\n');
console.log(`${results.length} Control Center scenarios passed; phase ${phase}; ${out}`);
