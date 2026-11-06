// Local UI verification only; all API responses are synthetic and no writes are allowed.
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {scenario} from './fixtures/discovery.mjs';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const origin = process.env.DISCOVERY_VISUAL_URL ?? 'http://127.0.0.1:3107';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(origin).hostname));
const secret = process.env.DISCOVERY_VISUAL_SECRET;
assert.ok(secret, 'A disposable local preview session secret is required');
const out = resolve(process.env.PREFERENCES_VISUAL_OUTPUT ?? '.quality/ui-preferences');
await mkdir(out, {recursive: true});
const now = Math.floor(Date.now() / 1000);
const payload = Buffer.from(JSON.stringify({sub: 'preferences-fixture', iat: now, exp: now + 3600, nonce: 'local-ui-test'})).toString('base64url');
const token = `${payload}.${createHmac('sha256', secret).update(payload).digest('base64url')}`;
const browser = await chromium.launch({executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable', headless: true});
const results = [];
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({viewport: {width, height: 1000}, hasTouch: width === 390, isMobile: width === 390});
    await context.addCookies([{name: 'noizy_team_session', value: token, url: origin}]);
    const apiCalls = [];
    const errors = [];
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== origin) return route.abort();
      if (!url.pathname.startsWith('/api/v1/')) return route.continue();
      apiCalls.push(route.request().method());
      const data = scenario('desktop');
      const response = url.pathname === '/api/v1/discovery' ? {...data, candidates: data.candidates.filter(item => item.status === url.searchParams.get('filter'))} : [];
      return route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(response)});
    });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${origin}/dashboard/vorschlaege`);
    await page.locator('.discovery-card').first().waitFor();
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'graphite');
    assert.equal(await page.locator('html').getAttribute('data-density'), 'compact');
    const sizes = {};
    for (const theme of ['graphite', 'light', 'dark']) for (const density of ['compact', 'comfortable']) {
      await page.getByRole('button', {name: 'Darstellung', exact: true}).click();
      await page.getByLabel('Theme', {exact: true}).selectOption(theme);
      await page.getByLabel('Informationsdichte', {exact: true}).selectOption(density);
      await page.waitForFunction(({theme, density}) => document.documentElement.dataset.theme === theme && document.documentElement.dataset.density === density, {theme, density}, {timeout: 15000});
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('button', {name: 'Darstellung', exact: true}).evaluate(element => element === document.activeElement), true);
      const metrics = await page.evaluate(() => {
        const card = document.querySelector('.discovery-card');
        const button = document.querySelector('.discovery-card button');
        const foreground = getComputedStyle(card).color.match(/[\d.]+/g).map(Number);
        const background = getComputedStyle(card).backgroundColor.match(/[\d.]+/g).map(Number);
        const lum = values => values.slice(0, 3).map(value => {const channel = value / 255; return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;}).reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
        const fg = lum(foreground); const bg = lum(background);
        return {cardHeight: card.getBoundingClientRect().height, buttonHeight: button.getBoundingClientRect().height, pageBackground: getComputedStyle(document.querySelector('.dashboard-shell')).backgroundColor, overflow: document.documentElement.scrollWidth > innerWidth, textContrast: (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05)};
      });
      assert.equal(metrics.overflow, false);
      assert.ok(metrics.textContrast >= 4.5);
      if (width === 390) assert.ok(metrics.buttonHeight >= 44, 'Touch targets remain at least 44px tall');
      if (theme === 'graphite') {
        assert.equal(metrics.pageBackground, 'rgb(28, 30, 34)');
        sizes[density] = metrics.cardHeight;
        await page.screenshot({path: `${out}/graphite-${density}-${width}.png`, fullPage: true});
      }
      results.push({width, theme, density, ...metrics});
    }
    assert.ok(sizes.compact < sizes.comfortable, 'Compact must actually display shorter cards');
    await page.getByRole('button', {name: 'Darstellung', exact: true}).click();
    await page.getByLabel('Theme', {exact: true}).selectOption('graphite');
    await page.getByLabel('Informationsdichte', {exact: true}).selectOption('compact');
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'graphite' && document.documentElement.dataset.density === 'compact', undefined, {timeout: 15000});
    await page.screenshot({path: `${out}/settings-${width}.png`, fullPage: false});
    await page.reload();
    await page.locator('.discovery-card').first().waitFor();
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'graphite');
    assert.equal(await page.locator('html').getAttribute('data-density'), 'compact');
    const sibling = await context.newPage();
    await sibling.goto(`${origin}/dashboard/vorschlaege`);
    await sibling.evaluate(() => {localStorage.setItem('noizy-theme', 'light'); localStorage.setItem('noizy-density', 'comfortable');});
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'light' && document.documentElement.dataset.density === 'comfortable', undefined, {timeout: 15000});
    assert.deepEqual(errors, []);
    assert.ok(apiCalls.every(method => method === 'GET'), 'Preferences must not issue API mutations');
    await context.close();
  }
} finally {await browser.close();}
await writeFile(`${out}/results.json`, JSON.stringify(results, null, 2) + '\n');
console.log(`${results.length} theme/density/viewport combinations passed; screenshots: ${out}`);
