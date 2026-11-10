import assert from 'node:assert/strict';
import {visualSession} from './fixtures/authSession.mjs';
import {mkdir, writeFile, readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture, mockControlCenterApi} from './fixtures/controlCenter.mjs';
import {candidate as discoveryCandidate} from './fixtures/discovery.mjs';

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
  const scenarios = phase === 12
    ? ['graphite', 'midnight', 'light'].flatMap(theme => ['compact', 'comfortable'].flatMap(density => [390, 768, 1280].map(width => [theme, density, width, 'populated'])))
    : phase === 11
    ? ['graphite', 'midnight', 'light'].flatMap(theme => ['compact', 'comfortable'].flatMap(density => [375, 390, 768, 1280].map(width => [theme, density, width, 'populated'])))
    : [6, 7, 8, 9, 10].includes(phase)
    ? ['graphite', 'midnight', 'light'].flatMap(theme => ['compact', 'comfortable'].flatMap(density => [1440, 390].map(width => [theme, density, width, 'populated'])))
    : [
    ['graphite', 'compact', 1440, 'populated'], ['midnight', 'comfortable', 1440, 'populated'], ['light', 'compact', 1440, 'populated'],
    ['graphite', 'comfortable', 768, 'populated'], ['midnight', 'compact', 390, 'populated'], ['light', 'comfortable', 390, 'populated'],
    ['graphite', 'compact', 1440, 'empty'], ['light', 'compact', 1440, 'error'], ['midnight', 'compact', 320, 'populated'],
  ];
  for (const [theme, density, width, state] of scenarios) {
    const context = await browser.newContext({...contextOptions, viewport: {width, height: 1000}, reducedMotion: phase === 12 ? 'reduce' : 'no-preference', hasTouch: width < 1024, isMobile: width < 768});
    await context.addCookies([{name: 'noizy_team_session', value: token, url: origin}]);
    await context.addInitScript(({theme, density}) => {localStorage.setItem('noizy-theme', theme); localStorage.setItem('noizy-density', density);}, {theme, density});
    const errors = [];
    const calls = [];
    const data = controlCenterFixture(state);
    if (phase === 12) data.genres = Array.from({length: 105}, (_, index) => ({id: `compact-${index}`, canonicalName: `canonical-${index}`, displayName: index === 0 ? 'Genre 000 – langer Anzeigename mit mehreren Wörtern' : `Genre ${String(index).padStart(3, '0')}`, active: index !== 0, aliases: index === 0 ? Array.from({length: 20}, (_, n) => ({id: `alias-${n}`, value: `Synonym ${n} – zusätzliche Beschreibung`})) : []}));
    await mockControlCenterApi(context, data, {state, onRequest: request => calls.push(request)});
    const saveFixture = phase === 7 ? await mockSaveResults(context, data, calls) : null;
    const importFixture = phase === 8 ? await mockImportRefresh(context, data, calls) : null;
    const genreImportFixture = [9, 11, 12].includes(phase) ? await mockGenreImport(context, data) : null;
    const resetFixture = phase === 10 ? await mockFactoryReset(context) : null;
    const nativeDialogs = [];
    const page = await context.newPage();
    if ([7, 8, 9, 11, 12].includes(phase)) page.on('dialog', dialog => {nativeDialogs.push(dialog.type()); void dialog.dismiss().catch(error => errors.push(error.message));});
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
      if (phase === 10 && route !== '/dashboard/settings') continue;
      if (phase === 8 && route !== '/dashboard/vorschlaege') continue;
      if (phase === 7 && !['/dashboard/partys', '/dashboard/locations'].includes(route)) continue;
      if ([6, 9, 11, 12].includes(phase) && route !== '/dashboard/settings/genres') continue;
      if (![6, 9, 11, 12].includes(phase) && route === '/dashboard/settings/genres') continue;
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
      if (phase === 10) await checkFactoryReset(page, resetFixture, name);
      if (phase === 8) await checkImportRefresh(page, importFixture, calls, name);
      if (phase === 11) {
        assert.equal(await page.getByRole('button', {name: /^(Aktivieren|Deaktivieren)$/}).count(), 0);
        assert.equal(await page.getByRole('button', {name: 'Alle Genres löschen', exact: true}).count(), 0, 'No missing deletion API is presented as available');
        const toolbar = page.getByRole('group', {name: 'Genre-Aktionen', exact: true});
        assert.equal(await toolbar.locator('.cc-button-primary').count(), 1);
        assert.equal(await toolbar.locator('.cc-button-primary').innerText(), '+ Genre anlegen');
        assert.equal(await toolbar.locator('details').getByRole('button', {name: 'Basisgenres wiederherstellen', exact: true, includeHidden: true}).count(), 1);
        if (width < 768) for (const action of await toolbar.getByRole('button').all()) {
          const bounds = await action.boundingBox(); assert.ok(bounds && bounds.height >= 44, `${name}: mobile action target`);
        }
        await page.screenshot({path: `${out}/${name}-genres-toolbar.png`, fullPage: true});
        const create = page.getByRole('button', {name: '+ Genre anlegen', exact: true});
        await create.click();
        const editor = page.getByRole('dialog');
        assert.equal(await editor.getByRole('checkbox').count(), 0);
        const canonical = editor.getByRole('textbox', {name: 'Kanonischer Name', exact: true});
        await canonical.fill('draft-wave');
        await page.keyboard.press('Escape'); // Native dirty guard is deliberately dismissed by this fixture.
        assert.equal(await editor.isVisible(), true);
        await canonical.fill('');
        await page.keyboard.press('Escape');
        await editor.waitFor({state: 'detached'});
        assert.equal(await create.evaluate(element => element === document.activeElement), true);
      }
      if (phase === 12) await checkGenreRedesign(page, data, name);
      if ([9, 11, 12].includes(phase)) await checkGenreImport(page, genreImportFixture, name);
      if (phase === 6) {
        await page.locator('[data-record-id]').first().waitFor();
        await page.locator('[data-record-id]').first().getByRole('button', {name: 'Bearbeiten', exact: true}).click();
        await page.getByRole('button', {name: '+ Alias hinzufügen', exact: true}).click();
        const aliasInput = page.getByRole('textbox', {name: 'Neuer Alias für Emo', exact: true});
        const aliasBounds = await aliasInput.boundingBox();
        assert.ok(aliasBounds && aliasBounds.width >= 120 && aliasBounds.height >= 40, `${name}: alias field must remain usable`);
        await aliasInput.fill('Fixture Alias');
        assert.equal(await aliasInput.inputValue(), 'Fixture Alias');
        await page.screenshot({path: `${out}/${name}-genres.png`, fullPage: true});
        await page.getByRole('button', {name: 'Aliaseingabe abbrechen', exact: true}).click();
        await page.keyboard.press('Escape');
        const opener = page.getByRole('button', {name: '+ Genre anlegen', exact: true});
        await opener.click();
        const editor = page.getByRole('dialog');
        await editor.waitFor();
        assert.equal(await editor.evaluate(element => element.contains(document.activeElement)), true);
        await editor.getByLabel('Kanonischer Name', {exact: true}).fill('fixture-wave');
        await editor.getByLabel('Anzeigename', {exact: true}).fill('Fixture Wave');
        await editor.getByRole('button', {name: 'Speichern', exact: true}).waitFor();
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
    } else if (phase === 8) {
      assert.deepEqual(nativeDialogs, [], `${name}: pending import opened a native confirmation dialog`);
      assert.ok(calls.every(call => call.method === 'GET' || (call.method === 'POST' && call.path === importFixture.confirmPath)), `${name}: unexpected discovery mutation`);
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
  const alert = editor.getByRole('alert').first();
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

async function mockImportRefresh(context, data, calls) {
  const id = 'import-fixture', entryId = 'visual/imported-entry';
  const known = data.parties[0];
  const item = discoveryCandidate(id, 'Fixture Calendar Import', {venue_id: data.venues[0].id, party_id: known.id, matching_class: 'MATCH',
    assessment: {recognition: {status: 'SINGLE', parties: [{id: known.id, name: known.name, matchedName: known.name}]}, verification: {status: 'VERIFIED', reasons: [], date: '2026-11-14'}, lifecycle: 'ACTIVE', automaticImportEligible: false, importBlockers: []}});
  data.discovery.candidates = [item];
  let releaseRefresh;
  const gate = new Promise(resolve => { releaseRefresh = resolve; });
  const stats = {confirmations: 0, refreshReads: 0};
  const confirmPath = `/api/v1/discovery/candidates/${id}/confirm`;
  const json = (route, body, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(body)});
  await context.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method();
    assert.equal(url.origin, new URL(origin).origin, 'Import fixture must stay on the loopback preview');
    const refresh = method === 'GET' && url.pathname === '/api/v1/calendar-entries' && stats.confirmations > 0;
    if (method === 'GET' && !refresh) return route.fallback();
    calls.push({path: url.pathname, method, body: request.postData()});
    if (url.pathname === confirmPath && method === 'POST') {
      stats.confirmations++;
      if (stats.confirmations > 1) return json(route, {message: 'Unexpected second fixture import'}, 409);
      const body = request.postDataJSON();
      data.entries.push({id: entryId, type: 'event', date: body.date, partyId: body.partyId, locationId: item.venue_id, startTime: '23:00', entryPrice: '', notes: '', mentionDjIds: [], eventFlyer: null, eventStatus: 'ACTIVE'});
      return json(route, {calendarEntryId: entryId});
    }
    if (refresh) {
      stats.refreshReads++;
      if (stats.refreshReads === 1) {
        await gate;
        // Keep the overview stale until the held read ends: only local confirmed-ID state can expose the link beforehand.
        data.discovery.candidates = [{...item, status: 'IMPORTED', calendar_entry_id: entryId}];
        return json(route, {message: 'Synthetic post-import calendar refresh outage'}, 503);
      }
      return json(route, data.entries);
    }
    return json(route, {message: 'Unexpected write in local import fixture'}, 500);
  });
  return {id, entryId, confirmPath, stats, releaseRefresh};
}

async function checkImportRefresh(page, fixture, calls, name) {
  const review = page.getByRole('region', {name: 'Prüfbedarf', exact: true});
  await review.locator('summary').first().click();
  await review.locator(`[data-record-id="${fixture.id}"]`).getByRole('button', {name: 'Veranstaltung prüfen', exact: true}).click();
  const editor = page.getByRole('dialog');
  const confirm = editor.getByRole('button', {name: 'Bestätigen & übernehmen', exact: true});
  assert.equal(await confirm.isEnabled(), true);
  const pendingRead = page.waitForRequest(request => new URL(request.url()).pathname === '/api/v1/calendar-entries' && request.method() === 'GET');
  await confirm.click();
  await pendingRead;
  const saved = editor.getByRole('region', {name: 'Gespeicherter Kalendereintrag', exact: true});
  const link = saved.getByRole('link', {name: 'Im Kalender anzeigen', exact: true});
  const expectedHref = `/dashboard/kalender?entry=${encodeURIComponent(fixture.entryId)}`;
  try {
    await saved.getByRole('status').filter({hasText: 'Ansichten werden aktualisiert'}).waitFor();
    assert.equal(await link.getAttribute('href'), expectedHref, `${name}: confirmed ID must precede refresh completion`);
    assert.equal(await confirm.isDisabled(), true);
    const close = editor.getByRole('button', {name: 'Dialog schließen', exact: true});
    assert.equal(await close.isDisabled(), true);
    await page.keyboard.press('Escape');
    await close.evaluate(element => element.click());
    assert.equal(await editor.isVisible(), true);
    assert.equal(fixture.stats.confirmations, 1);
    await saved.scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: pending import overflow`);
    await page.screenshot({path: `${out}/${name}-import-confirmed-pending.png`});
  } finally {fixture.releaseRefresh();}
  await saved.getByRole('alert').filter({hasText: 'Kalenderimport ist bestätigt. Noch nicht aktualisiert: Kalender'}).waitFor();
  const retry = saved.getByRole('button', {name: 'Ansichten erneut laden', exact: true});
  await page.waitForFunction(() => !!document.querySelector('[role="dialog"] button[data-modal-close]:not(:disabled)'));
  assert.equal(await retry.isEnabled(), true);
  assert.equal(await confirm.isDisabled(), true);
  assert.equal(await link.getAttribute('href'), expectedHref);
  await saved.scrollIntoViewIfNeeded();
  await captureSaveFailure(page, editor, `${name}-import-refresh-failed`);
  const retryBounds = await retry.boundingBox();
  assert.ok(retryBounds && retryBounds.y >= 0 && retryBounds.y + retryBounds.height <= page.viewportSize().height, `${name}: refresh retry must be visible in the failure screenshot`);
  assert.equal(await editor.evaluate(element => element.scrollWidth > element.clientWidth), false, `${name}: import drawer overflow`);
  const beforeRetry = calls.length;
  await retry.click();
  await page.getByRole('status').filter({hasText: 'Termin bestätigt und in den Kalender übernommen. Ansichten aktualisiert.'}).waitFor();
  await page.waitForFunction(() => !!document.querySelector('[role="dialog"] button[data-modal-close]:not(:disabled)'));
  assert.equal(fixture.stats.refreshReads, 2);
  assert.equal(fixture.stats.confirmations, 1);
  assert.ok(calls.slice(beforeRetry).length > 0 && calls.slice(beforeRetry).every(call => call.method === 'GET'), `${name}: retry must only refresh views`);
  assert.equal(await saved.getByRole('alert').count(), 0);
  assert.equal(await link.getAttribute('href'), expectedHref);
  assert.equal(await confirm.isDisabled(), true);
  const writes = calls.filter(call => call.method !== 'GET');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].path, fixture.confirmPath);
  assert.deepEqual(JSON.parse(writes[0].body), {partyId: 'party-0', date: '2026-11-14', inquiryStatus: 'NONE', dmText: '', seriesAlias: null});
}


async function mockGenreImport(context, data) {
  const source = await readFile(new URL('../../backend/src/test/resources/genres/catalog-2026-11-09.csv', import.meta.url));
  const rows = source.toString('utf8').replace(/^\uFEFF/, '').trim().split(/\r?\n/).slice(1).map((line, index) => {
    const [canonicalName, displayName, active, aliases, gruppe, status, quelle, hinweis] = line.split(';');
    return {line: index + 2, canonicalName, displayName, active: active === 'true', aliases: aliases ? aliases.split(' | ') : [], metadata: {gruppe, status, quelle, hinweis}, action: 'NEU'};
  });
  const stats = {previews: 0, applies: 0, fail: true, source, rows, holdReload: phase === 11, releaseReload: null};
  if (phase === 11) await context.route('**/api/v1/genres', async route => {
    assert.equal(route.request().method(), 'GET', 'This browser fixture never writes a genre or deletion endpoint');
    if (stats.holdReload && stats.applies === 2 && !stats.fail) {
      stats.holdReload = false;
      await new Promise(resolve => { stats.releaseReload = resolve; });
    }
    return route.fulfill({contentType: 'application/json', body: JSON.stringify(data.genres)});
  });
  await context.route('**/api/v1/genres/import{,/preview}', async route => {
    assert.equal(route.request().method(), 'POST');
    const preview = route.request().url().endsWith('/preview');
    const payload = route.request().postDataJSON();
    assert.equal(payload.schemaVersion, 1);
    assert.ok(payload.csv?.includes('Dark Electro'));
    if (preview) stats.previews++; else stats.applies++;
    const conflict = !preview && stats.fail;
    if (!preview && !conflict) data.genres = rows.map((row, index) => ({...row, id: `import-${index}`, aliases: row.aliases.map((value, n) => ({id: `alias-${index}-${n}`, value}))}));
    return route.fulfill({status: conflict ? 409 : 200, contentType: 'application/json', body: JSON.stringify({total: 104, created: 104, unchanged: 0, supplemented: 0, inactive: 24, aliases: 85, aliasesAdded: 85, valid: !conflict, applied: !preview && !conflict, rows, conflicts: conflict ? [{line: 9, genre: 'Rock’n’Roll', field: 'aliases', message: 'Alias gehört inzwischen zu einem anderen Genre.'}] : []})});
  });
  return stats;
}

async function checkGenreImport(page, fixture, name) {
  const opener = page.getByRole('button', {name: 'Genres importieren', exact: true});
  await opener.click();
  const dialog = page.getByRole('dialog');
  const confirm = dialog.getByRole('button', {name: 'Import bestätigen', exact: true});
  assert.equal(await confirm.isDisabled(), true);
  await dialog.locator('input[type="file"]').setInputFiles({name: 'catalog.csv', mimeType: 'text/csv', buffer: fixture.source});
  assert.equal(fixture.previews, 0); assert.equal(fixture.applies, 0);
  await dialog.getByRole('button', {name: 'Vorschau prüfen', exact: true}).click();
  await dialog.getByRole('status').waitFor();
  assert.match(await dialog.innerText(), /104 Genres geprüft.*24 inaktiv.*85 Aliase/);
  await dialog.locator('summary').click();
  await dialog.getByText('Synth-Punk', {exact: true}).waitFor();
  const actionBox = await confirm.boundingBox();
  assert.ok(actionBox && actionBox.y >= 0 && actionBox.y + actionBox.height <= page.viewportSize().height, 'Sticky import actions stay visible with expanded catalogue');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await confirm.focus();
  await page.keyboard.press('Tab');
  assert.equal(await dialog.getByRole('button', {name: 'Abbrechen', exact: true}).evaluate(element => element === document.activeElement), true);
  await confirm.click();
  await dialog.getByText('1 Konflikte – kein Import möglich', {exact: true}).waitFor();
  assert.equal(await confirm.isDisabled(), true);
  if (phase === 11) {
    const reportUrl = await dialog.getByRole('link', {name: 'Konfliktbericht herunterladen', exact: true}).getAttribute('href');
    const report = JSON.parse(decodeURIComponent(reportUrl.slice(reportUrl.indexOf(',') + 1)));
    assert.equal(report.conflicts[0].field, 'aliases');
    await captureSaveFailure(page, dialog, `${name}-genres-error-contrast`);
  }
  assert.equal(await dialog.locator('input[type="file"]').evaluate(element => element.files[0].name), 'catalog.csv');
  await dialog.locator('summary').click();
  await dialog.getByRole('alert').last().scrollIntoViewIfNeeded();
  await page.screenshot({path: `${out}/${name}-genre-import-conflict.png`});
  fixture.fail = false;
  await dialog.getByRole('button', {name: 'Vorschau prüfen', exact: true}).click();
  await confirm.waitFor({state: 'visible'});
  await page.waitForFunction(() => ![...document.querySelectorAll('button')].find(button => button.textContent === 'Import bestätigen')?.disabled);
  await confirm.click();
  if (phase === 11) {
    await dialog.getByRole('status').filter({hasText: 'Import bestätigt. Die Katalogliste wird aktualisiert.'}).waitFor();
    await page.keyboard.press('Escape'); assert.equal(await dialog.isVisible(), true);
    assert.equal(await confirm.isDisabled(), true);
    const deadline = Date.now() + 5000;
    while (!fixture.releaseReload && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
    assert.ok(fixture.releaseReload, `${name}: catalogue reload must start`);
    fixture.releaseReload();
  }
  await dialog.waitFor({state: 'detached'});
  if (phase === 11) assert.equal(await opener.evaluate(element => element === document.activeElement), true, `${name}: focus after delayed catalogue reload`);
  await page.getByRole('status').filter({hasText: '104 Genres geprüft · 104 neu angelegt · 85 Aliase übernommen'}).waitFor();
  assert.equal(fixture.applies, 2); assert.equal(fixture.previews, 2);
  await page.getByRole('searchbox', {name: 'Genres und Aliase durchsuchen'}).fill('Shoegaze');
  await page.getByRole('region', {name: 'Genre-Katalog', exact: true}).locator('strong').filter({hasText: /^Shoegaze$/}).waitFor();
  await page.screenshot({path: `${out}/${name}-genre-import-success.png`});
}


// Browser interception only: no reset reaches Next's gateway or any backend.
async function mockFactoryReset(context) {
  const fixture = {reads: 0, writes: 0, resolve: null};
  await context.route('**/api/admin/factory-reset', async route => {
    if (route.request().method() === 'GET') {
      fixture.reads++;
      return route.fulfill({contentType: 'application/json', body: JSON.stringify({recordCount: 152, mediaFileCount: 12, tables: [], challenge: 'a'.repeat(48)})});
    }
    fixture.writes++;
    const outcome = await new Promise(resolve => {fixture.resolve = resolve;});
    fixture.resolve = null;
    return route.fulfill({status: outcome.status, contentType: 'application/json', body: JSON.stringify(outcome.body)});
  });
  return fixture;
}

async function checkFactoryReset(page, fixture, name) {
  const opener = page.getByRole('button', {name: 'Werkseinstellungen öffnen', exact: true});
  await opener.click();
  const modal = page.getByRole('dialog');
  const phrase = modal.locator('#factory-reset-phrase');
  const password = modal.locator('#factory-reset-password');
  const acknowledged = modal.getByRole('checkbox');
  const submit = modal.getByRole('button', {name: 'Kompletten Reset ausführen', exact: true});
  async function confirm() {
    await phrase.fill('Kompletter Reset');
    await password.fill('disposable-browser-reset-password');
    await acknowledged.check();
    page.once('dialog', dialog => dialog.accept());
    await submit.click();
    await modal.getByRole('button', {name: 'Werkseinstellungen werden zurückgesetzt …', exact: true}).waitFor();
    assert.equal(await phrase.isDisabled(), true, name);
    await page.keyboard.press('Escape');
    assert.equal(await modal.isVisible(), true, name);
    assert.equal(fixture.resolve instanceof Function, true);
  }
  await confirm();
  fixture.resolve({status: 409, body: {error: 'Datenstand verändert. Neue Vorschau erforderlich.', outcomeUnknown: false}});
  await phrase.waitFor();
  await page.waitForFunction(() => document.querySelector('#factory-reset-phrase')?.value === '');
  assert.equal(await password.inputValue(), '');
  assert.equal(await acknowledged.isChecked(), false);
  assert.equal(await submit.isDisabled(), true);
  assert.equal(fixture.writes, 1);
  assert.equal(fixture.reads, 2);
  await confirm();
  fixture.resolve({status: 200, body: {deletedRecords: 152, deletedFiles: 12, filesNotDeleted: 0, partial: true, message: 'Datenbank-Reset ist bestätigt. Der technische Abschluss ist gestört.'}});
  await modal.getByRole('status').filter({hasText: 'Der technische Abschluss ist gestört.'}).waitFor();
  assert.equal((await modal.innerText()).includes('0 Mediendatei(en)'), false);
  await page.screenshot({path: `${out}/${name}-reset-partial.png`});
  await page.keyboard.press('Escape');
  await modal.waitFor({state: 'detached'});
  assert.equal(await opener.evaluate(element => element === document.activeElement), true);
  await opener.click();
  await confirm();
  fixture.resolve({status: 503, body: {error: 'Antwort nicht eindeutig.', outcomeUnknown: true}});
  await modal.getByRole('alert').filter({hasText: 'Reset-Ausgang unbestätigt.'}).waitFor();
  const reads = fixture.reads;
  await page.keyboard.press('Escape');
  await modal.waitFor({state: 'detached'});
  await opener.click();
  await modal.getByRole('alert').filter({hasText: 'Reset-Ausgang unbestätigt.'}).waitFor();
  assert.equal(fixture.reads, reads, name);
  assert.equal(fixture.writes, 3, name);
  assert.equal(await submit.count(), 0);
  for (let index = 0; index < 6; index++) {
    await page.keyboard.press('Tab');
    assert.equal(await modal.evaluate(element => element.contains(document.activeElement)), true, name);
  }
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, name);
  await page.screenshot({path: `${out}/${name}-reset-unknown.png`});
  await page.keyboard.press('Escape');
  await modal.waitFor({state: 'detached'});
}


async function checkGenreRedesign(page, data, name) {
  const overview = page.getByRole('region', {name: 'Genre-Katalog', exact: true});
  await overview.locator('[data-record-id]').first().waitFor();
  assert.equal(await overview.locator('[data-record-id]').count(), 25);
  assert.equal(await overview.locator('form, input:not([type="search"])').count(), 0);
  const first = overview.locator('[data-record-id="compact-0"]');
  assert.equal(await first.locator('li').count(), 4);
  assert.equal(await first.getByRole('button').count(), 2); // Expand and the only editing entrance.
  const expand = first.getByRole('button', {name: /^16 weitere Aliase/});
  await expand.click(); assert.equal(await first.locator('li').count(), 20);
  assert.equal(await first.locator('[aria-expanded]').getAttribute('aria-expanded'), 'true');
  await first.getByRole('button', {name: /^Weniger Aliase/}).click();
  const search = overview.getByRole('searchbox');
  await search.fill('Synonym 19'); assert.equal(await overview.locator('[data-record-id]').count(), 1);
  await search.fill('canonical-0'); assert.equal(await overview.locator('[data-record-id]').count(), 1);
  await search.fill('');
  await overview.getByRole('button', {name: 'Weiter', exact: true}).click();
  assert.equal(await overview.locator('[data-record-id="compact-25"]').count(), 1);
  await overview.getByRole('button', {name: 'Zurück', exact: true}).click();
  await page.screenshot({path: `${out}/${name}-genres-overview.png`, fullPage: true});
  const edit = first.getByRole('button', {name: 'Bearbeiten', exact: true});
  await edit.click();
  const drawer = page.getByRole('dialog');
  assert.equal(await drawer.evaluate(element => element.classList.contains('cc-drawer')), true);
  assert.equal(await drawer.getByRole('textbox').count(), 2);
  assert.equal(await drawer.getByRole('checkbox').count(), 0);
  assert.equal(await drawer.locator('#genre-aliases li').count(), 20);
  const save = drawer.getByRole('button', {name: 'Speichern', exact: true});
  const footer = await save.boundingBox();
  assert.ok(footer && footer.y >= 0 && footer.y + footer.height <= page.viewportSize().height, `${name}: drawer footer stays visible`);
  assert.equal(await drawer.evaluate(element => element.scrollWidth > element.clientWidth), false, `${name}: drawer overflow`);
  await page.screenshot({path: `${out}/${name}-genres-drawer.png`});
  await drawer.getByRole('button', {name: '+ Alias hinzufügen', exact: true}).click();
  const alias = drawer.getByRole('textbox', {name: /^Neuer Alias für/});
  assert.equal(await alias.evaluate(element => element === document.activeElement), true);
  await alias.fill('Ungespeicherter Alias');
  await page.keyboard.press('Escape'); assert.equal(await drawer.isVisible(), true);
  await drawer.getByRole('button', {name: 'Aliaseingabe abbrechen', exact: true}).click();
  await page.keyboard.press('Escape'); await drawer.waitFor({state: 'detached'});
  assert.equal(await edit.evaluate(element => element === document.activeElement), true);
  if (page.viewportSize().width < 640) {
    for (const action of await overview.getByRole('button').all()) {
      if (!await action.isVisible()) continue;
      const box = await action.boundingBox(); assert.ok(box.height >= 44, `${name}: touch target ${await action.innerText()}`);
    }
  }
  // The same real page must also distinguish one record and a genuinely empty catalogue.
  const original = data.genres;
  for (const size of [1, 0]) {
    data.genres = original.slice(0, size);
    await page.reload();
    await page.waitForFunction(() => document.querySelector('[aria-label="Genre-Katalog"]')?.getAttribute('aria-busy') === 'false');
    assert.equal(await overview.locator('[data-record-id]').count(), size);
    if (!size) {
      assert.match(await overview.innerText(), /Noch keine Genres erfasst/);
      assert.equal(await overview.getByRole('button', {name: '+ Genre anlegen', exact: true}).isEnabled(), true);
      assert.equal(await overview.getByRole('button', {name: 'Genres importieren', exact: true}).isEnabled(), true);
    }
  }
  assert.equal(await page.getByRole('button', {name: /Genres löschen/}).count(), 0);
  data.genres = original;
}
