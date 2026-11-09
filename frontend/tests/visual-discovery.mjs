// Run against an isolated local Next server; see docs/DISCOVERY-REDESIGN.md.
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { scenario } from './fixtures/discovery.mjs';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const origin = process.env.DISCOVERY_VISUAL_URL ?? 'http://127.0.0.1:3107';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(origin).hostname), 'Local preview only');
const secret = process.env.DISCOVERY_VISUAL_SECRET;
assert.ok(secret, 'Use the same disposable secret as the isolated Next process');
const out = resolve(process.env.DISCOVERY_VISUAL_OUTPUT ?? 'docs/screenshots/discovery');
await mkdir(out, { recursive: true });
const now = Math.floor(Date.now() / 1000);
const payload = Buffer.from(JSON.stringify({sub: 'visual-fixture', iat: now, exp: now + 3600, nonce: 'local-ui-test'})).toString('base64url');
const token = `${payload}.${createHmac('sha256', secret).update(payload).digest('base64url')}`;
const browser = await chromium.launch({executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable', headless: true});
const results = [];
try {
  for (const [name, width, theme] of [['desktop', 1440, 'dark'], ['empty', 1440, 'light'], ['history', 1440, 'dark'], ['running', 1440, 'dark'], ['source-error', 1440, 'light'], ['mobile', 390, 'dark'], ['mobile-navigation', 390, 'light'], ['mobile-dialog', 390, 'light']]) {
    const context = await browser.newContext({viewport: {width, height: 1000}, deviceScaleFactor: 1});
    await context.addCookies([{name: 'noizy_team_session', value: token, url: origin}]);
    await context.addInitScript(theme => localStorage.setItem('noizy-theme', theme), theme);
    const data = scenario(name);
    const calls = [];
    const errors = [];
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== origin) return route.abort();
      if (!url.pathname.startsWith('/api/v1/')) return route.continue();
      calls.push({path: url.pathname, method: route.request().method()});
      let response = [];
      if (url.pathname === '/api/v1/discovery') {
        const filter = url.searchParams.get('filter');
        const candidates = ['RELEVANT', 'UNCLEAR', 'NEEDS_REVIEW', 'READY', 'NEEDS_INQUIRY'].includes(filter) ? data.candidates.filter(item => (filter === 'RELEVANT' ? item.matching_class !== 'UNCLASSIFIABLE' : filter === 'UNCLEAR' ? item.matching_class === 'UNCLASSIFIABLE' : item.status === filter)) : data.history.filter(item => filter === 'IMPORTED' ? item.status === 'IMPORTED' : filter === 'AUTOMATIC_IGNORED' ? item.status === 'IGNORED' : true);
        response = {...data, candidates};
      } else if (url.pathname === '/api/v1/venues') response = [{id: 'venue', name: 'Club Beispiel', instagram: ''}];
      return route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(response)});
    });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${origin}/dashboard/vorschlaege`);
    await page.getByRole('heading', {name: 'Discovery', exact: true}).waitFor();
    await page.waitForFunction(() => !document.querySelector('main')?.textContent.includes('Discovery wird geladen'), undefined, {timeout: 15000});
    await page.evaluate(() => document.fonts.ready);
    if (name === 'empty') assert.equal(await page.locator('.discovery-card').count(), 0);
    else assert.equal(await page.locator('.discovery-card').count(), 4);
    if (name === 'history') {
      assert.ok(!calls.some(call => call.path.includes('history')));
      await page.locator('.discovery-history > summary').click();
      await page.waitForFunction(() => document.querySelectorAll('.discovery-history-content > .discovery-history-list > li').length === 20, undefined, {timeout: 15000});
      await page.getByRole('button', {name: 'Weitere Einträge anzeigen', exact: true}).click();
      assert.equal(await page.locator('.discovery-history-content > .discovery-history-list > li').count(), 40);
      await page.locator('.discovery-history').evaluate(element => window.scrollTo(0, element.getBoundingClientRect().top + scrollY - 80));
    }
    if (name === 'running') assert.equal(await page.getByRole('button', {name: 'Suche läuft …', exact: true}).isDisabled(), true);
    if (name === 'source-error') {
      await page.getByRole('button', {name: 'Quellen ansehen', exact: true}).click();
      await page.locator('#discovery-tools[open]').waitFor();
      await page.evaluate(() => window.scrollTo(0, 0));
    }
    if (name === 'mobile-navigation') {
      await page.getByRole('button', {name: 'Menü', exact: true}).click();
      assert.equal(await page.getByRole('link', {name: 'Discovery', exact: true}).getAttribute('aria-current'), 'page');
    }
    if (name === 'mobile-dialog') {
      const trigger = page.locator('.discovery-card').first().getByRole('button');
      await trigger.click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor();
      await page.getByLabel('Name im Portfolio').fill('Meine neue Reihe');
      await page.screenshot({path: `${out}/${name}.png`, fullPage: false});
      await dialog.locator('button:enabled').last().focus();
      await page.keyboard.press('Tab');
      assert.equal(await dialog.locator('[data-modal-close]').evaluate(element => element === document.activeElement), true);
      await page.keyboard.press('Escape');
      assert.equal(await dialog.count(), 0);
      assert.equal(await trigger.evaluate(element => element === document.activeElement), true);
    } else await page.screenshot({path: `${out}/${name}.png`, fullPage: name === 'history' ? false : true});
    if (name === 'mobile-navigation') {
      await page.getByRole('link', {name: 'Discovery', exact: true}).focus();
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('button', {name: 'Menü', exact: true}).getAttribute('aria-expanded'), 'false');
      await page.getByRole('button', {name: 'Menü', exact: true}).click();
      await page.getByRole('link', {name: 'Discovery', exact: true}).click();
      assert.equal(await page.getByRole('button', {name: 'Menü', exact: true}).getAttribute('aria-expanded'), 'false');
    }
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: horizontal overflow`);
    const contrasts = await page.evaluate(() => {
      const rgb = value => value.match(/[\d.]+/g)?.map(Number) ?? [];
      const luminance = values => values.slice(0, 3).map(value => {const channel = value / 255; return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;}).reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
      return [...document.querySelectorAll('h1, .discovery-metric strong, .discovery-card h3, .discovery-button, .dashboard-nav-link')].filter(element => element.getClientRects().length && !element.disabled).map(element => {
        let ancestor = element;
        while (ancestor.parentElement && getComputedStyle(ancestor).backgroundColor === 'rgba(0, 0, 0, 0)') ancestor = ancestor.parentElement;
        const fg = luminance(rgb(getComputedStyle(element).color));
        const bg = luminance(rgb(getComputedStyle(ancestor).backgroundColor));
        return {text: element.textContent, ratio: (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05)};
      });
    });
    assert.ok(contrasts.every(item => item.ratio >= 4.5), `${name}: insufficient contrast ${JSON.stringify(contrasts.filter(item => item.ratio < 4.5))}`);
    assert.deepEqual(errors, [], `${name}: browser errors`);
    assert.ok(!calls.some(call => call.method !== 'GET'), 'Screenshots must never write API data');
    results.push({scenario: name, width, theme, browserErrors: errors.length, minimumTextContrast: Math.min(...contrasts.map(item => item.ratio)), screenshot: `${name}.png`, passed: true});
    await context.close();
  }
} finally { await browser.close(); }
await writeFile(`${out}/results.json`, JSON.stringify(results, null, 2) + '\n');
console.log(`${results.length} visual scenarios passed; screenshots: ${out}`);
