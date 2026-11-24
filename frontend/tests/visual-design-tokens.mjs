// Local token verification only: synthetic API responses, loopback origin, no writes.
// Reuses the Control Center browser fixtures; screenshots stay under .quality/.
import assert from 'node:assert/strict';
import {visualSession} from './fixtures/authSession.mjs';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture, mockControlCenterApi} from './fixtures/controlCenter.mjs';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const origin = process.env.DISCOVERY_VISUAL_URL ?? 'http://127.0.0.1:3107';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(origin).hostname));
const out = resolve(process.env.DESIGN_TOKENS_VISUAL_OUTPUT ?? '.quality/design-tokens');
await mkdir(out, {recursive: true});

// Source-level guard for the single token source: only globals.css declares
// theme values, and the dead dark theme must not survive in either stylesheet.
const globalsCss = await readFile(new URL('../src/app/globals.css', import.meta.url), 'utf8');
const controlCss = await readFile(new URL('../src/app/control-center.css', import.meta.url), 'utf8');
assert.ok(!globalsCss.includes('data-theme="dark"') && !controlCss.includes('data-theme="dark"'));
for (const value of ['--background', '--surface', '--foreground', '--muted', '--accent', '--danger']) {
  assert.equal(new RegExp(`^\\s*${value}\\s*:`, 'm').test(controlCss), false, `${value} must only be declared in globals.css`);
}
for (const name of ['surface', 'line', 'muted', 'accent', 'danger']) {
  assert.match(globalsCss, new RegExp(`--color-${name}:\\s*var\\(--${name}\\)`), `@theme inline must export ${name}`);
}

// Values captured from the shipped design before consolidation; any visual
// change in a supported theme/density/viewport combination fails this test.
const expected = {
  graphite: {
    tokens: {'--background': '#11151c', '--foreground': '#eff3f7', '--surface': '#1b222c', '--surface-raised': '#252e3b', '--surface-muted': '#293443', '--line': '#354050', '--line-strong': '#596b82', '--muted': '#abb8c9', '--muted-strong': '#ced8e4', '--focus': '#53b4d8', '--danger': '#ffa5a5', '--accent': '#53b4d8', '--accent-ink': '#091820'},
    body: {background: 'rgb(17, 21, 28)', color: 'rgb(239, 243, 247)'},
    shell: 'rgb(17, 21, 28)',
    panel: {background: 'rgb(27, 34, 44)', border: 'rgb(53, 64, 80)'},
  },
  midnight: {
    tokens: {'--background': '#0b1020', '--foreground': '#f2f2ff', '--surface': '#17213a', '--surface-raised': '#202e4c', '--surface-muted': '#283957', '--line': '#344569', '--line-strong': '#6076a5', '--muted': '#b2bcda', '--muted-strong': '#d7dcf1', '--focus': '#babbff', '--danger': '#ffa5b1', '--accent': '#a5a6ff', '--accent-ink': '#11152b'},
    body: {background: 'rgb(11, 16, 32)', color: 'rgb(242, 242, 255)'},
    shell: 'rgb(11, 16, 32)',
    panel: {background: 'rgb(23, 33, 58)', border: 'rgb(52, 69, 105)'},
  },
  light: {
    tokens: {'--background': '#f4f6f8', '--foreground': '#182433', '--surface': '#fff', '--surface-raised': '#f0f3f7', '--surface-muted': '#e7edf3', '--line': '#d4dbe4', '--line-strong': '#8291a4', '--muted': '#536477', '--muted-strong': '#35475b', '--focus': '#126ba0', '--danger': '#a5223e', '--accent': '#126ba0', '--accent-ink': '#fff'},
    // The legacy !important Tailwind override on body is intentionally kept.
    body: {background: 'rgb(244, 244, 241)', color: 'rgb(24, 36, 51)'},
    shell: 'rgb(244, 246, 248)',
    panel: {background: 'rgb(255, 255, 255)', border: 'rgb(212, 219, 228)'},
  },
};

const {token, contextOptions} = await visualSession(origin);
const browser = await chromium.launch({executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable', headless: true});
const results = [];
try {
  for (const width of [390, 1440]) {
    for (const theme of ['graphite', 'midnight', 'light']) {
      const context = await browser.newContext({...contextOptions, viewport: {width, height: 1000}, hasTouch: width === 390, isMobile: width === 390});
      await context.addCookies([{name: 'noizy_team_session', value: token, url: origin}]);
      await context.addInitScript(({theme}) => {localStorage.setItem('noizy-theme', theme); localStorage.setItem('noizy-density', 'compact');}, {theme});
      const calls = [];
      const errors = [];
      await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
      await mockControlCenterApi(context, controlCenterFixture(), {onRequest: request => calls.push(request.method)});
      const page = await context.newPage();
      page.setDefaultTimeout(15000);
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(`${origin}/dashboard`);
      await page.getByRole('heading', {name: 'Dein Arbeitsbereich', exact: true}).waitFor();
      assert.equal(await page.locator('html').getAttribute('data-theme'), theme);
      assert.equal(await page.locator('html').getAttribute('data-density'), 'compact');
      const metrics = await page.evaluate(() => {
        const style = getComputedStyle(document.documentElement);
        const names = ['--background', '--foreground', '--surface', '--surface-raised', '--surface-muted', '--line', '--line-strong', '--muted', '--muted-strong', '--focus', '--danger', '--accent', '--accent-ink', '--color-surface', '--color-line', '--color-muted', '--color-accent', '--color-danger'];
        const tokens = Object.fromEntries(names.map(name => [name, style.getPropertyValue(name).trim()]));
        const sample = selector => {
          const element = document.querySelector(selector);
          if (!element) throw new Error(`Missing ${selector}`);
          const computed = getComputedStyle(element);
          return {background: computed.backgroundColor, color: computed.color, border: computed.borderColor};
        };
        return {tokens, body: sample('body'), shell: sample('.dashboard-shell'), panel: sample('.cc-panel'), overflow: document.documentElement.scrollWidth > innerWidth};
      });
      const reference = expected[theme];
      for (const [name, value] of Object.entries(reference.tokens)) {
        assert.equal(metrics.tokens[name], value, `${theme}/${width}: ${name} changed`);
      }
      for (const name of ['surface', 'line', 'muted', 'accent', 'danger']) {
        assert.equal(metrics.tokens[`--color-${name}`], reference.tokens[`--${name}`], `${theme}/${width}: @theme inline export ${name}`);
      }
      assert.deepEqual({background: metrics.body.background, color: metrics.body.color}, reference.body, `${theme}/${width}: body colors changed`);
      assert.equal(metrics.shell.background, reference.shell, `${theme}/${width}: shell background changed`);
      assert.deepEqual({background: metrics.panel.background, border: metrics.panel.border}, reference.panel, `${theme}/${width}: panel colors changed`);
      assert.equal(metrics.overflow, false, `${theme}/${width}: horizontal overflow`);
      assert.deepEqual(errors, [], `${theme}/${width}: browser errors`);
      assert.ok(calls.every(method => method === 'GET'), `${theme}/${width}: API mutation`);
      await page.screenshot({path: `${out}/${theme}-${width}.png`, fullPage: true});
      results.push({theme, width, passed: true});
      await context.close();
    }
  }
} finally {await browser.close();}
await writeFile(`${out}/results.json`, JSON.stringify(results, null, 2) + '\n');
console.log(`${results.length} design-token/viewport combinations passed; screenshots: ${out}`);
