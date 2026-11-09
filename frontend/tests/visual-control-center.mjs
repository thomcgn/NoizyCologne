import assert from 'node:assert/strict';
import {visualSession} from './fixtures/authSession.mjs';
import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture, mockControlCenterApi} from './fixtures/controlCenter.mjs';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const origin = process.env.DISCOVERY_VISUAL_URL ?? 'http://127.0.0.1:3107';
assert.ok(['localhost', '127.0.0.1'].includes(new URL(origin).hostname));
const phase = Number(process.env.CC_PHASE ?? 1);
const out = resolve(process.env.CC_OUTPUT ?? `.quality/control-center/phase-${phase}`);
await mkdir(out, {recursive: true});
const {token, contextOptions} = await visualSession(origin);
const browser = await chromium.launch({executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable', headless: true});
const results = [];
try {
  const scenarios = [6, 7].includes(phase)
    ? ['graphite', 'midnight', 'light'].flatMap(theme => ['compact', 'comfortable'].flatMap(density => [1440, 390].map(width => [theme, density, width, 'populated'])))
    : [
    ['graphite', 'compact', 1440, 'populated'], ['midnight', 'comfortable', 1440, 'populated'], ['light', 'compact', 1440, 'populated'],
    ['graphite', 'comfortable', 768, 'populated'], ['midnight', 'compact', 390, 'populated'], ['light', 'comfortable', 390, 'populated'],
    ['graphite', 'compact', 1440, 'empty'], ['light', 'compact', 1440, 'error'], ['midnight', 'compact', 320, 'populated'],
  ];
  for (const [theme, density, width, state] of scenarios) {
    const context = await browser.newContext({...contextOptions, viewport: {width, height: 1000}, hasTouch: width < 1024, isMobile: width < 768});
    await context.addCookies([{name: 'noizy_team_session', value: token, url: origin}]);
    await context.addInitScript(({theme, density}) => {localStorage.setItem('noizy-theme', theme); localStorage.setItem('noizy-density', density);}, {theme, density});
    const errors = [];
    const calls = [];
    const data = controlCenterFixture(state);
    await mockControlCenterApi(context, data, {state, onRequest: request => calls.push(request)});
    const saveFixture = phase === 7 ? await mockSaveResults(context, data, calls) : null;
    const nativeDialogs = [];
    const page = await context.newPage();
    if (phase === 7) page.on('dialog', dialog => {nativeDialogs.push(dialog.type()); void dialog.dismiss().catch(error => errors.push(error.message));});
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
    if (phase === 4) {
      await page.getByRole('button', {name: 'Globale Suche öffnen', exact: true}).click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor();
      const search = dialog.getByRole('searchbox', {name: 'Global suchen'});
      assert.equal(await search.evaluate(element => element === document.activeElement), true);
      await search.fill('DJ Beispiel');
      if (state === 'populated') {
        const result = dialog.getByRole('link', {name: 'DJ Beispiel dj_0', exact: true});
        await result.waitFor();
        await page.keyboard.press('ArrowDown');
        assert.equal(await result.evaluate(element => element === document.activeElement), true);
        await page.screenshot({path: `${out}/${name}-search.png`});
        await result.click();
        await page.getByRole('dialog').getByRole('textbox').first().waitFor();
        assert.equal(await page.getByRole('dialog').getByRole('textbox').first().inputValue(), 'DJ Beispiel');
        await page.keyboard.press('Escape');
      } else {
        await dialog.getByRole(state === 'error' ? 'alert' : 'status').first().waitFor();
        await page.keyboard.press('Escape');
      }
      await page.getByRole('dialog').waitFor({state: 'detached'});
      await page.keyboard.press('Control+k');
      await page.getByRole('dialog').waitFor();
      await page.keyboard.press('Escape');
      await page.getByRole('dialog').waitFor({state: 'detached'});
    }
    for (const route of ['/dashboard/vorschlaege', '/dashboard/partys', '/dashboard/djs', '/dashboard/locations', '/dashboard/kalender', '/dashboard/redaktion', '/dashboard/settings', '/dashboard/automatisierung', '/dashboard/settings/genres']) {
      if (phase === 4) continue;
      if (phase === 7 && !['/dashboard/partys', '/dashboard/locations'].includes(route)) continue;
      if (phase === 6 && route !== '/dashboard/settings/genres') continue;
      if (phase !== 6 && route === '/dashboard/settings/genres') continue;
      if (phase === 5 && route !== '/dashboard/redaktion') continue;
      if (phase === 1 && !['/dashboard/settings', '/dashboard/automatisierung'].includes(route)) continue;
      if (phase === 2 && route !== '/dashboard/vorschlaege') continue;
      if (phase === 3 && !['/dashboard/partys', '/dashboard/djs', '/dashboard/locations', '/dashboard/kalender'].includes(route)) continue;
      await page.goto(`${origin}${route}`);
      await page.locator('main h1').waitFor();
      await page.waitForTimeout(180);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name} ${route}: horizontal overflow`);
      if (phase === 7) {
        try { await checkSaveResults(page, saveFixture, route, name); }
        catch (error) {
          await page.screenshot({path: `${out}/${name}-failure.png`, fullPage: true});
          await writeFile(`${out}/${name}-failure.txt`, await page.locator('body').innerText());
          throw error;
        }
      }
      if (phase === 6) {
        await page.locator('[data-record-id]').first().waitFor();
        const aliasInput = page.getByRole('textbox', {name: 'Neuer Alias für Emo', exact: true});
        const aliasBounds = await aliasInput.boundingBox();
        assert.ok(aliasBounds && aliasBounds.width >= 120 && aliasBounds.height >= 40, `${name}: alias field must remain usable`);
        await aliasInput.fill('Fixture Alias');
        assert.equal(await aliasInput.inputValue(), 'Fixture Alias');
        await page.screenshot({path: `${out}/${name}-genres.png`, fullPage: true});
        const opener = page.getByRole('button', {name: '+ Genre anlegen', exact: true});
        await opener.click();
        const editor = page.getByRole('dialog');
        await editor.waitFor();
        assert.equal(await editor.evaluate(element => element.contains(document.activeElement)), true);
        await editor.getByLabel('Kanonischer Name', {exact: true}).fill('fixture-wave');
        await editor.getByLabel('Anzeigename', {exact: true}).fill('Fixture Wave');
        await editor.getByRole('button', {name: 'Speichern und schließen', exact: true}).waitFor();
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: genre editor overflow`);
        await page.screenshot({path: `${out}/${name}-genre-editor.png`, fullPage: true});
        page.once('dialog', dialog => dialog.dismiss());
        await page.keyboard.press('Escape');
        assert.equal(await editor.isVisible(), true);
        page.once('dialog', dialog => dialog.accept());
        await editor.getByRole('button', {name: 'Abbrechen', exact: true}).click();
        await editor.waitFor({state: 'detached'});
        assert.equal(await opener.evaluate(element => element === document.activeElement), true);
      }
      if (phase === 5 && route === '/dashboard/redaktion') {
        if (state === 'populated') {
          await page.goto(`${origin}/dashboard/redaktion#record=entry-0`);
          const editor = page.locator('#editorial-editor');
          const tabs = page.getByRole('navigation', {name: 'Redaktionsansicht'});
          const caption = editor.getByLabel('Caption', {exact: true});
          await caption.waitFor({state: 'visible'});
          assert.equal(await caption.inputValue(), data.drafts[0].caption);
          await caption.fill('Manuell geändert\nZeilen bleiben erhalten.');
          await editor.getByRole('status').filter({hasText: 'Ungespeicherte Änderungen'}).waitFor();
          if (width < 1024) {
            await tabs.getByRole('button', {name: 'Vorschau', exact: true}).click();
            assert.equal(await editor.isVisible(), false);
          }
          assert.ok((await page.locator('#editorial-preview').innerText()).includes('Manuell geändert'));
          await page.evaluate(() => window.scrollTo(0, 0));
          await page.screenshot({path: `${out}/${name}-editorial.png`, fullPage: true});
          if (width < 1024) await tabs.getByRole('button', {name: 'Bearbeiten', exact: true}).click();
          assert.equal(await caption.inputValue(), 'Manuell geändert\nZeilen bleiben erhalten.');
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
        } else if (state === 'error') await page.getByRole('alert').first().waitFor();
        else await page.getByText('Noch keine Veranstaltungen vorhanden.', {exact: true}).waitFor();
      }
      if (phase === 3) {
        const collection = page.locator('.cc-collection');
        if (state === 'populated') {
          await collection.locator('[data-record-id]').first().waitFor();
          const count = await collection.locator('[data-record-id]').count();
          assert.ok(count > 0 && count <= 25);
          const search = collection.getByRole('searchbox');
          await search.fill('no-such-fixture-record');
          assert.equal(await collection.locator('[data-record-id]').count(), 0);
          await search.fill('');
          const trigger = collection.locator('[data-record-open]').first();
          await trigger.click();
          const drawer = page.getByRole('dialog');
          await drawer.waitFor();
          assert.equal(await drawer.evaluate(element => element.contains(document.activeElement)), true);
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
          await page.screenshot({path: `${out}/${name}-${route.split('/').at(-1)}-drawer.png`});
          await page.keyboard.press('Escape');
          await drawer.waitFor({state: 'detached'});
          assert.equal(await trigger.evaluate(element => element === document.activeElement), true);
        } else if (state === 'empty') {
          await collection.getByRole('status').filter({hasText: '0 Einträge'}).waitFor();
        } else await page.getByRole('alert').first().waitFor();
        await page.screenshot({path: `${out}/${name}-${route.split('/').at(-1)}.png`, fullPage: true});
      }
      if (phase === 2 && route === '/dashboard/vorschlaege') {
        const fresh = page.getByRole('region', {name: 'Neue Partyreihen', exact: true});
        await fresh.waitFor();
        await page.screenshot({path: `${out}/${name}-discovery.png`, fullPage: true});
        if (state === 'populated') {
          await fresh.locator('[data-record-id]').first().waitFor();
          assert.equal(await fresh.locator('[data-record-id]').count(), 25);
          await fresh.getByRole('button', {name: 'Weiter', exact: true}).click();
          assert.equal(await fresh.locator('[data-record-id]').count(), 23);
          await fresh.getByRole('searchbox').fill('MIDNIGHT');
          assert.equal(await fresh.locator('[data-record-id]').count(), 1);
          await fresh.getByRole('button', {name: 'Karten', exact: true}).click();
          assert.equal(await fresh.locator('article').count(), 1);
          await fresh.getByRole('button', {name: 'Ins Portfolio übernehmen', exact: true}).click();
          const drawer = page.getByRole('dialog');
          await drawer.waitFor();
          assert.equal(await drawer.evaluate(element => element.contains(document.activeElement)), true);
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: drawer overflow`);
          await page.screenshot({path: `${out}/${name}-drawer.png`, fullPage: false});
          const close = drawer.getByRole('button', {name: 'Dialog schließen', exact: true});
          const last = drawer.getByRole('button', {name: 'Speichern', exact: true});
          await last.focus();
          await page.keyboard.press('Tab');
          assert.equal(await close.evaluate(element => element === document.activeElement), true);
          await page.keyboard.press('Shift+Tab');
          assert.equal(await last.evaluate(element => element === document.activeElement), true);
          await page.keyboard.press('Escape');
          await drawer.waitFor({state: 'detached'});
          await page.goto(`${origin}/dashboard/vorschlaege#discovery-tools`);
          await page.waitForFunction(() => document.getElementById('discovery-tools')?.open);
          await page.goto(`${origin}/dashboard/vorschlaege#discovery-history`);
          await page.waitForFunction(() => document.getElementById('discovery-history')?.open);
        } else if (state === 'error') {
          await page.getByRole('alert').first().waitFor();
          assert.ok(await page.getByText('Ergebnisse derzeit nicht verfügbar.', {exact: false}).count());
        } else {
          await page.getByText('Keine neuen passenden Partyreihen', {exact: true}).waitFor();
        }
      }
    }
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: overflow`);
    assert.deepEqual(errors, [], `${name}: browser errors`);
    if (phase === 7) {
      const allowed = new Set(['POST /api/v1/parties', 'PUT /api/v1/discovery/parties/save-party/aliases', 'POST /api/v1/venues', 'POST /api/v1/discovery/onboard', 'POST /api/v1/discovery/onboard/save-venue/sources']);
      assert.ok(calls.every(call => call.method === 'GET' || allowed.has(`${call.method} ${call.path}`)), `${name}: unexpected write, calendar import or duplicate CRUD`);
      assert.deepEqual(nativeDialogs, [], `${name}: pending/save flow opened a native confirmation dialog`);
    } else assert.ok(calls.every(call => call.method === 'GET'), `${name}: screenshot scenario attempted an API write`);
    results.push({theme, density, width, state, passed: true});
    await context.close();
  }
} finally {await browser.close();}
await writeFile(`${out}/results.json`, JSON.stringify(results, null, 2) + '\n');
console.log(`${results.length} Control Center scenarios passed; phase ${phase}; ${out}`);

// All mutations below are fulfilled in this loopback browser context, never forwarded.
async function mockSaveResults(context, data, calls) {
  let releaseAlias;
  const aliasGate = new Promise(resolve => { releaseAlias = resolve; });
  const stats = {partyCreates: 0, partyUpdates: 0, aliasWrites: 0, aliasReads: 0, reloadReads: 0, venueCreates: 0, venueUpdates: 0, sourceWrites: 0};
  const json = (route, body, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(body)});
  await context.route('**/api/v1/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    assert.equal(url.origin, new URL(origin).origin, 'Save fixtures must stay on the local preview origin');
    const path = url.pathname;
    const method = request.method();
    const aliases = path === '/api/v1/discovery/parties/save-party/aliases';
    const refresh = method === 'GET' && path === '/api/v1/calendar-entries' && stats.aliasWrites >= 2;
    if (method === 'GET' && !aliases && !refresh) return route.fallback();
    calls.push({path, method, body: request.postData()});
    if (path === '/api/v1/parties' && method === 'POST') {
      stats.partyCreates++;
      const saved = {...request.postDataJSON(), id: 'save-party', defaultFlyer: null};
      data.parties.push(saved);
      return json(route, saved, 201);
    }
    if (path === '/api/v1/parties/save-party' && method === 'PUT') {
      stats.partyUpdates++;
      return json(route, {...data.parties.find(item => item.id === 'save-party'), ...request.postDataJSON(), id: 'save-party'});
    }
    if (aliases) {
      if (method === 'GET') {stats.aliasReads++; return json(route, ['Stored fixture alias']);}
      if (method === 'PUT') {
        stats.aliasWrites++;
        if (stats.aliasWrites === 1) {await aliasGate; return json(route, {message: 'Synthetic alias outage'}, 503);}
        return route.fulfill({status: 204});
      }
    }
    if (refresh) {
      stats.reloadReads++;
      return stats.reloadReads === 1 ? json(route, {message: 'Synthetic calendar refresh outage'}, 503) : json(route, data.entries);
    }
    if (path === '/api/v1/discovery/onboard' && method === 'POST') {
      return json(route, {previewToken: 'save-fixture-preview', websiteUrl: 'https://save-fixture.example/', suggestedName: 'Fixture Save Location', checkedAt: '2026-11-09T10:00:00Z', eventCount: 0, partyCount: 0, knownSeriesCount: 0, incomplete: true, problems: [], sources: []});
    }
    if (path === '/api/v1/venues' && method === 'POST') {
      stats.venueCreates++;
      const saved = {...request.postDataJSON(), id: 'save-venue'};
      data.venues.push(saved);
      return json(route, saved, 201);
    }
    if (path === '/api/v1/venues/save-venue' && method === 'PUT') {
      stats.venueUpdates++;
      return json(route, {...data.venues.find(item => item.id === 'save-venue'), ...request.postDataJSON(), id: 'save-venue'});
    }
    if (path === '/api/v1/discovery/onboard/save-venue/sources' && method === 'POST') {
      stats.sourceWrites++;
      return stats.sourceWrites === 1 ? json(route, {message: 'Synthetic source registration outage'}, 503) : route.fulfill({status: 204});
    }
    return json(route, {message: 'Unexpected mutation in local save fixture'}, 500);
  });
  return {stats, releaseAlias};
}

async function checkSaveResults(page, fixture, route, name) {
  const party = route.endsWith('/partys');
  const opener = page.viewportSize().width > 800
    ? page.getByRole('button', {name: party ? 'Partyreihe anlegen' : 'Location anlegen', exact: true})
    : page.locator('main').getByRole('button', {name: '+ Neu anlegen', exact: true});
  await opener.click();
  const editor = page.getByRole('dialog');
  await editor.getByLabel('Name', {exact: true}).fill(party ? 'Fixture Save Party' : 'Fixture Save Location');
  if (party) {
    await editor.getByLabel('Standard-Startuhrzeit (24 Stunden)', {exact: true}).fill('23:00');
    await editor.locator('summary').filter({hasText: 'Alternative Namen für die Partysuche'}).click();
    await editor.getByRole('textbox', {name: /^Explizite Aliase \(ein Name pro Zeile\)/}).fill('Fixture retained alias');
    const pending = page.waitForRequest(request => new URL(request.url()).pathname === '/api/v1/discovery/parties/save-party/aliases' && request.method() === 'PUT');
    await editor.getByRole('button', {name: 'Übernehmen', exact: true}).click();
    await pending;
    try {
      assert.equal(await editor.locator('form').getAttribute('aria-busy'), 'true');
      const close = editor.getByRole('button', {name: 'Dialog schließen', exact: true});
      const cancel = editor.getByRole('button', {name: 'Abbrechen', exact: true});
      assert.equal(await close.isDisabled(), true);
      assert.equal(await cancel.isDisabled(), true);
      await page.keyboard.press('Escape');
      // Native clicks on disabled X/Cancel must not initiate a discard dialog.
      await close.evaluate(element => element.click());
      await cancel.evaluate(element => element.click());
      assert.equal(await editor.isVisible(), true);
      assert.equal(await editor.getByLabel('Name', {exact: true}).inputValue(), 'Fixture Save Party');
    } finally {fixture.releaseAlias();}
    await editor.getByRole('alert').filter({hasText: 'Partyreihe ist gespeichert; Discovery-Aliase'}).waitFor();
    assert.equal(await editor.getByRole('textbox', {name: /^Explizite Aliase \(ein Name pro Zeile\)/}).inputValue(), 'Fixture retained alias');
    assert.equal(fixture.stats.partyCreates, 1); assert.equal(fixture.stats.partyUpdates, 0); assert.equal(fixture.stats.aliasReads, 0);
    await captureSaveFailure(page, editor, `${name}-party-partial`);
    await editor.getByRole('button', {name: 'Offene Schritte fortsetzen', exact: true}).click();
    await editor.getByRole('alert').filter({hasText: 'Nur die Kalenderansicht konnte nicht aktualisiert werden'}).waitFor();
    assert.equal(fixture.stats.partyCreates, 1); assert.equal(fixture.stats.partyUpdates, 0); assert.equal(fixture.stats.aliasWrites, 2);
    await captureSaveFailure(page, editor, `${name}-party-refresh`);
    await editor.getByRole('button', {name: 'Offene Schritte fortsetzen', exact: true}).click();
    await editor.waitFor({state: 'detached'});
    assert.equal(fixture.stats.reloadReads, 2); assert.equal(fixture.stats.aliasWrites, 2);
    assert.equal(fixture.stats.partyCreates, 1); assert.equal(fixture.stats.partyUpdates, 0);
    await page.getByRole('status').filter({hasText: 'Partyreihe „Fixture Save Party“ gespeichert.'}).waitFor();
  } else {
    await editor.locator('summary').filter({hasText: 'Website & Partysuche'}).click();
    await editor.getByLabel('Offizielle Website', {exact: true}).fill('https://save-fixture.example/');
    await editor.getByRole('button', {name: 'Website automatisch prüfen', exact: true}).click();
    await editor.getByRole('status').filter({hasText: 'Geprüft:'}).waitFor();
    await editor.getByRole('button', {name: 'Übernehmen', exact: true}).click();
    await editor.getByRole('alert').filter({hasText: 'Location ist gespeichert; Quellen übernehmen'}).waitFor();
    assert.equal(await editor.getByLabel('Name', {exact: true}).inputValue(), 'Fixture Save Location');
    assert.equal(fixture.stats.venueCreates, 1); assert.equal(fixture.stats.venueUpdates, 0);
    await captureSaveFailure(page, editor, `${name}-location-partial`);
    await editor.getByRole('button', {name: 'Offene Schritte fortsetzen', exact: true}).click();
    await editor.waitFor({state: 'detached'});
    assert.equal(fixture.stats.venueCreates, 1); assert.equal(fixture.stats.venueUpdates, 0); assert.equal(fixture.stats.sourceWrites, 2);
    await page.getByRole('status').filter({hasText: 'Location „Fixture Save Location“ gespeichert.'}).waitFor();
  }
  await page.waitForFunction(() => document.activeElement?.matches('button.cc-context-action, button.cc-page-primary'));
  assert.equal(await opener.evaluate(element => element === document.activeElement), true, `${name}: successful save restores trigger focus; active=${await page.evaluate(() => document.activeElement?.outerHTML.slice(0, 500))}`);
}

async function captureSaveFailure(page, editor, name) {
  const alert = editor.getByRole('alert');
  const colors = await alert.evaluate(element => {
    let background = element;
    while (background && !/^rgb\(/.test(getComputedStyle(background).backgroundColor)) background = background.parentElement;
    return {foreground: getComputedStyle(element).color, background: background && getComputedStyle(background).backgroundColor};
  });
  const luminance = color => {
    assert.match(color, /^rgb\(/, 'Opaque error foreground/background required for contrast measurement');
    const channels = color.match(/[\d.]+/g).slice(0, 3).map(value => Number(value) / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  };
  const a = luminance(colors.foreground), b = luminance(colors.background);
  assert.ok((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 4.5, `${name}: error contrast must meet 4.5:1`);
  await alert.scrollIntoViewIfNeeded();
  await page.screenshot({path: `${out}/${name}.png`});
}
