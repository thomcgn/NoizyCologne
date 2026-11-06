import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import ts from 'typescript';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost' });
for (const name of ['window', 'document', 'HTMLElement', 'HTMLDetailsElement', 'Event', 'MouseEvent', 'KeyboardEvent']) {
  globalThis[name] = dom.window[name];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
// Browser code uses window.setInterval and the global clearInterval interchangeably.
window.setInterval = globalThis.setInterval;
window.clearInterval = globalThis.clearInterval;
const require = createRequire(import.meta.url);
const React = require('react');
const { createRoot } = require('react-dom/client');
const { act, createElement: h, useState, StrictMode } = React;
const filename = new URL('../src/app/dashboard/DashboardModal.tsx', import.meta.url);
const compiled = ts.transpileModule(readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText;
const componentModule = { exports: {} };
new Function('require', 'module', 'exports', compiled)(require, componentModule, componentModule.exports);
const { default: DashboardModal, useModalFocus } = componentModule.exports;
let root;
afterEach(() => {
  if (root) act(() => root.unmount());
  root = null;
  document.body.replaceChildren();
});
function mount(component) {
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  act(() => root.render(h(StrictMode, null, component)));
}
function click(element) { act(() => element.click()); }
function key(key, shiftKey = false) {
  const event = new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true });
  act(() => document.activeElement.dispatchEvent(event));
  return event;
}
function type(input, text) {
  input.focus();
  for (const letter of text) {
    act(() => {
      Object.getOwnPropertyDescriptor(input instanceof dom.window.HTMLTextAreaElement ? dom.window.HTMLTextAreaElement.prototype : dom.window.HTMLInputElement.prototype, 'value').set.call(input, input.value + letter);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    assert.ok(document.activeElement === input, "Typed field must retain focus after each character");
  }
}
function Form({ custom = false }) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState('');
  const [checked, setChecked] = useState(false);
  const [choice, setChoice] = useState('a');
  const [error, setError] = useState(false);
  const [closedValue, setClosedValue] = useState('');
  const close = () => { setClosedValue(value); setOpen(false); };
  const children = h('form', { onSubmit: e => { e.preventDefault(); if (value === 'valid') close(); else setError(true); } },
    h('input', { 'aria-label': 'First' }),
    h('input', { 'aria-label': 'Second', value, onChange: e => setValue(e.target.value) }),
    h('input', { type: 'checkbox', checked, onChange: e => setChecked(e.target.checked) }),
    h('select', { value: choice, onChange: e => setChoice(e.target.value) }, h('option', { value: 'a' }, 'A'), h('option', { value: 'b' }, 'B')),
    error && h('p', { role: 'alert' }, 'Please correct the input'),
    h('button', { type: 'button', onClick: close }, 'Cancel'),
    h('button', { type: 'submit' }, 'Save'));
  return h(React.Fragment, null,
    h('button', { id: 'trigger', onClick: () => setOpen(true) }, 'Open'),
    h('output', { id: 'closed-value' }, closedValue),
    custom ? h(CustomDialog, { open, close }, children) : h(DashboardModal, { title: 'Test', open, onClose: close }, children));
}
function CustomDialog({ open, close, children }) {
  const ref = React.useRef(null);
  useModalFocus(open, ref, close);
  return open && h('div', { ref, role: 'dialog', tabIndex: -1 }, children);
}
function openForm(custom = false) {
  mount(h(Form, { custom }));
  const trigger = document.querySelector('#trigger');
  trigger.focus();
  click(trigger);
  assert.equal(document.activeElement.getAttribute('aria-label'), 'First');
  return trigger;
}
for (const custom of [false, true]) {
  test(`${custom ? 'calendar layout' : 'DashboardModal'}: typing, field changes, checkbox/select and latest Escape callback`, () => {
    const trigger = openForm(custom);
    const second = document.querySelector('[aria-label="Second"]');
    type(second, 'abcdef');
    assert.equal(second.value, 'abcdef');
    const first = document.querySelector('[aria-label="First"]');
    type(first, 'first');
    const checkbox = document.querySelector('[type="checkbox"]');
    checkbox.focus();
    click(checkbox);
    assert.equal(checkbox.checked, true);
    assert.equal(document.activeElement, checkbox);
    const select = document.querySelector('select');
    select.focus();
    act(() => { select.value = 'b'; select.dispatchEvent(new Event('change', { bubbles: true })); });
    assert.equal(document.activeElement, select);
    key('Escape');
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(document.querySelector('#closed-value').textContent, 'abcdef');
    assert.equal(document.activeElement, trigger);
    click(trigger);
    assert.equal(document.activeElement, first.isConnected ? first : document.querySelector('[aria-label="First"]'));
  });
  test(`${custom ? 'calendar layout' : 'DashboardModal'}: Tab boundaries, cancel, validation error and successful save restore focus`, () => {
    const trigger = openForm(custom);
    const controls = document.querySelectorAll('[role="dialog"] input, [role="dialog"] select, [role="dialog"] button');
    const first = controls[0];
    const last = controls[controls.length - 1];
    last.focus();
    assert.equal(key('Tab').defaultPrevented, true);
    const boundaryFirst = document.activeElement;
    assert.equal(boundaryFirst, document.querySelector('[role="dialog"] button, [role="dialog"] input'));
    assert.equal(key('Tab', true).defaultPrevented, true);
    assert.equal(document.activeElement, last);
    click(last);
    assert.ok(document.querySelector('[role="alert"]'));
    assert.equal(document.activeElement, last);
    const second = document.querySelector('[aria-label="Second"]');
    type(second, 'valid');
    click(last);
    assert.equal(document.activeElement, trigger);
    click(trigger);
    assert.notEqual(document.activeElement, first);
    click([...document.querySelectorAll('button')].find(b => b.textContent === 'Cancel'));
    assert.equal(document.activeElement, trigger);
  });
}
test('closing by unmount restores a connected trigger; closed rerenders do not steal focus', () => {
  const trigger = document.createElement('button');
  document.body.append(trigger);
  trigger.focus();
  mount(h(DashboardModal, { open: true, title: 'Unmount', onClose() {} }, h('input')));
  assert.notEqual(document.activeElement, trigger);
  act(() => root.render(null));
  assert.equal(document.activeElement, trigger);
  const external = document.createElement('button');
  document.body.append(external);
  external.focus();
  for (let index = 0; index < 3; index++) {
    act(() => root.render(h(DashboardModal, { open: false, title: 'Closed', onClose() {} })));
    assert.equal(document.activeElement, external);
  }
});
test('disabled, hidden and collapsed controls are excluded; empty dialog traps Tab', () => {
  mount(h(DashboardModal, { open: true, title: 'Hidden', onClose() {} },
    h('input', { disabled: true }), h('input', { type: 'hidden' }),
    h('div', { hidden: true }, h('input')),
    h('details', null, h('summary', null, 'More'), h('input')),
    h('input', { id: 'visible' })));
  assert.equal(document.activeElement.id, 'visible');
  // The close button remains a valid boundary even without form controls.
  act(() => root.render(h(CustomDialog, { open: true, close() {} })));
  assert.equal(document.activeElement.getAttribute('role'), 'dialog');
  assert.equal(key('Tab').defaultPrevented, true);
  assert.equal(document.activeElement.getAttribute('role'), 'dialog');
});

// Load the actual forms with an in-memory catalogue. No application API is contacted.
const sourceCache = new Map();
let catalogue;
function loadSource(url) {
  const path = url.pathname;
  if (sourceCache.has(path)) return sourceCache.get(path);
  const loadedModule = { exports: {} };
  sourceCache.set(path, loadedModule.exports);
  const code = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const localRequire = (specifier) => {
    if (specifier === 'next/link') return { __esModule: true, default: props => h('a', props) };
    if (specifier.endsWith('/CatalogProvider')) return {
      ...loadSource(new URL('../src/app/dashboard/CatalogProvider.tsx', import.meta.url)),
      useCatalog: () => catalogue,
    };
    if (specifier.endsWith('/DashboardModal')) return componentModule.exports;
    if (specifier.startsWith('.')) {
      const base = new URL(specifier, url);
      for (const extension of ['.tsx', '.ts']) {
        const candidate = new URL(base.href + extension);
        try { readFileSync(candidate); } catch { continue; }
        return loadSource(candidate);
      }
    }
    return require(specifier);
  };
  new Function('require', 'module', 'exports', code)(localRequire, loadedModule, loadedModule.exports);
  sourceCache.set(path, loadedModule.exports);
  return loadedModule.exports;
}
const party = { id: 'party', name: 'Test Party', instagram: 'party', color: '#123456', residentDjIds: [], otherDjIds: [], organizerIds: [], standardGenres: [], djMentionDefault: 'manual', recurrence: null, defaultFlyer: null };
const venue = { id: 'venue', name: 'Test Venue', instagram: 'venue', websiteUrl: '', eventsUrl: '', discoveryEnabled: false };
const actor = { id: 'actor', name: 'Test DJ', instagram: 'dj', kind: 'person', roles: ['dj'], collectiveIds: [] };
const entry = { id: 'entry', date: '2027-01-02', type: 'event', locationId: 'venue', partyId: 'party', entryPrice: '10', startTime: '22:00', notes: '', mentionDjIds: [], eventFlyer: null };
const genre = { id: 'genre', canonicalName: 'rock', displayName: 'Rock', active: true, aliases: [] };
// jsdom does not compile Tailwind. Supply the two utility baselines, then apply
// the real global stylesheet so theme overrides are tested on the actual forms.
const themeStyles = `.bg-white { background-color: #ffffff; } .text-black { color: #000000; }\n${readFileSync(new URL('../src/app/globals.css', import.meta.url), 'utf8').replace('@import "tailwindcss";', '')}`;
function contrastRatio(foreground, background) {
  function luminance(color) {
    const channels = color.match(/\d+(?:\.\d+)?/g).slice(0, 3).map(value => {
      const channel = Number(value) / 255;
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  }
  const first = luminance(foreground);
  const second = luminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}
const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
function button(text) {
  const result = [...document.querySelectorAll('button')].find(element => element.textContent.trim().includes(text));
  assert.ok(result, `Button ${text} exists`);
  return result;
}
for (const [name, file, props, opener] of [
  ['Genres', 'settings/genres/page.tsx', {}, 'Genre anlegen'],
  ['Partyreihen', 'portfolio/page.tsx', {}, 'Neu anlegen'],
  ['Locations', 'portfolio/page.tsx', { initialCategory: 'locations' }, 'Neu anlegen'],
  ['DJs / Akteure', 'portfolio/akteure/page.tsx', {}, 'Neu anlegen'],
  ['Kalendereinträge', 'kalender/page.tsx', {}, 'Neuer Eintrag'],
]) {
  test(`${name}: actual create/edit forms preserve focus and button contrast across themes`, async () => {
    catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [entry] };
    globalThis.ResizeObserver = class { observe() {} disconnect() {} };
    globalThis.fetch = async () => new Response(JSON.stringify([genre]), { status: 200, headers: { 'Content-Type': 'application/json' } });
    const Page = loadSource(new URL(`../src/app/dashboard/${file}`, import.meta.url)).default;
    await act(async () => { mount(h(Page, props)); });
    const style = document.createElement('style');
    style.textContent = themeStyles;
    document.body.append(style);
    for (const mode of ['create', 'edit']) {
      const trigger = button(mode === 'create' ? opener : 'Bearbeiten');
      trigger.focus();
      click(trigger);
      const dialog = document.querySelector('[role="dialog"]');
      assert.ok(dialog);
      const inputs = [...dialog.querySelectorAll('input')].filter(input => ['text', 'url'].includes(input.type));
      assert.ok(inputs.length >= 1);
      type(inputs[Math.min(1, inputs.length - 1)], 'testing');
      for (const checkbox of dialog.querySelectorAll('input[type="checkbox"]:not(:disabled)')) {
        checkbox.focus();
        click(checkbox);
        assert.equal(document.activeElement, checkbox);
      }
      for (const select of dialog.querySelectorAll('select')) {
        if (select.options.length < 2) continue;
        select.focus();
        act(() => {
          select.selectedIndex = select.selectedIndex === 0 ? 1 : 0;
          select.dispatchEvent(new Event('change', { bubbles: true }));
        });
        assert.equal(document.activeElement, select);
      }
      const lastButton = [...dialog.querySelectorAll('button:not(:disabled)')].at(-1);
      lastButton.focus();
      key('Tab');
      assert.equal(document.activeElement, dialog.querySelector('button'));
      key('Tab', true);
      assert.equal(document.activeElement, lastButton);
      const focusedField = dialog.querySelector('input:not(:disabled):not([type="hidden"]), select, textarea');
      const primaryButtons = dialog.querySelectorAll('button.bg-white.text-black');
      assert.ok(primaryButtons.length > 0, `${name} ${mode}: primary actions are included in the contrast check`);
      for (const theme of ['dark', 'light']) {
        for (const width of [390, 1280]) {
          document.documentElement.dataset.theme = theme;
          window.innerWidth = width;
          focusedField.focus();
          act(() => window.dispatchEvent(new Event('resize')));
          assert.equal(document.activeElement, focusedField);
          for (const primary of primaryButtons) {
            const computed = getComputedStyle(primary);
            assert.ok(contrastRatio(computed.color, computed.backgroundColor) >= 4.5,
              `${name} ${mode}: ${primary.textContent} must remain legible in ${theme} mode at ${width}px`);
          }
        }
      }
      key('Escape');
      assert.equal(document.querySelector('[role="dialog"]'), null);
      assert.equal(document.activeElement, trigger);
      click(trigger);
      click(button('Abbrechen'));
      assert.equal(document.activeElement, trigger);
      assert.equal(document.querySelector('[role="dialog"]'), null);
    }
  });
}

test('suggestion review and source/profile dialogs preserve edits and focus', async () => {
  catalogue = { parties: [party], venues: [venue] };
  const overview = { automaticallyImported: 0, counts: [], runs: [],
    candidates: [{ id: 'candidate', title: 'Party', status: 'NEEDS_REVIEW', inquiry_status: 'PREPARED', dm_text: '', venue_name: 'Venue', evidence: '' }],
    sources: [{ id: 'source', venue_id: 'venue', venue_name: 'Venue', url: 'https://example.org', approved: false, status: 'NOT_CHECKED', item_selector: '', title_selector: '', date_selector: '' }] };
  globalThis.fetch = async () => new Response(JSON.stringify(overview), { headers: { 'Content-Type': 'application/json' } });
  const Page = loadSource(new URL('../src/app/dashboard/vorschlaege/page.tsx', import.meta.url)).default;
  await act(async () => {
    mount(h(Page));
  });
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, 20));
  });
  for (const [opener, selector] of [['Veranstaltung prüfen', 'textarea'], ['Quelle / Profil bearbeiten', 'input[type="url"]']]) {
    // The sources section is intentionally collapsible.
    for (const details of document.querySelectorAll('details')) details.open = true;
    const trigger = button(opener);
    trigger.focus();
    click(trigger);
    const input = document.querySelector(`[role="dialog"] ${selector}`);
    type(input, 'testing');
    key('Escape');
    assert.equal(document.activeElement, trigger);
    assert.equal(document.querySelector('[role="dialog"]'), null);
  }
});

test('calendar information dialog traps focus and restores its trigger', async () => {
  catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [entry] };
  const Page = loadSource(new URL('../src/app/dashboard/kalender/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  const trigger = document.querySelector('button[title="Test Party"]');
  assert.ok(trigger);
  trigger.focus();
  click(trigger);
  const dialog = document.querySelector('[role="dialog"]');
  assert.ok(dialog);
  const buttons = dialog.querySelectorAll('button');
  buttons[buttons.length - 1].focus();
  key('Tab');
  assert.equal(document.activeElement, buttons[0]);
  key('Escape');
  assert.equal(document.activeElement, trigger);
});

// Switching between the calendar's two layouts must retain the page trigger.
test('calendar information → editor → Escape restores the original page trigger', async () => {
  catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [entry] };
  const Page = loadSource(new URL('../src/app/dashboard/kalender/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  const trigger = document.querySelector('button[title="Test Party"]');
  trigger.focus();
  click(trigger);
  click(button('Bearbeiten'));
  assert.ok(document.querySelector('#calendar-entry-dialog-title'));
  key('Escape');
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
});

test('discovery reset shows preview and requires a second explicit manual-rejection confirmation', async () => {
  const calls = [];
  const started = [];
  const preview = { token: 'a'.repeat(64), affectedCount: 3, preservedManual: 1, missingRaw: 2, enabledLocations: 1,
    items: [{ id: 'candidate', title: 'Unknown Party', venue: 'Venue', oldStatus: 'IGNORED', newStatus: 'NEEDS_REVIEW', classification: 'POSSIBLE_MATCH', reason: 'Genre-Match: Emo' }] };
  globalThis.fetch = async (url, options) => {
    calls.push({ url, body: JSON.parse(options.body) });
    return new Response(JSON.stringify(url.endsWith('/preview') ? preview : { operationId: 'operation' }));
  };
  const Dialog = loadSource(new URL('../src/app/dashboard/vorschlaege/DiscoveryResetDialog.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Dialog, { initialMode: 'IGNORED', onClose() {}, onStarted: id => started.push(id) })); });
  assert.ok(document.querySelector('[aria-label="Reset-Vorschau"]').textContent.includes('3 Kandidaten'));
  const checkbox = document.querySelector('input[type="checkbox"]');
  assert.equal(checkbox.checked, false);
  checkbox.focus();
  await act(async () => { checkbox.click(); });
  assert.equal(document.activeElement, checkbox);
  assert.equal(button('Neubewertung bestätigen').disabled, true);
  click(document.querySelectorAll('input[type="checkbox"]')[1]);
  assert.equal(button('Neubewertung bestätigen').disabled, false);
  await act(async () => { button('Neubewertung bestätigen').click(); });
  const request = calls.find(call => call.url.endsWith('/reset'));
  assert.equal(request.body.includeManualRejected, true);
  assert.equal(request.body.confirmation, 'MANUELL ABGELEHNTE ERNEUT PRÜFEN');
  assert.equal(request.body.previewToken, preview.token);
  assert.equal(request.body.mode, 'IGNORED');
  assert.deepEqual(started, ['operation']);
});

test('discovery reset invalidates the preview when switching to a complete rescan', async () => {
  const requests = [];
  globalThis.fetch = async (url, options) => {
    const body = JSON.parse(options.body);
    requests.push(body);
    return new Response(JSON.stringify({ token: body.mode, affectedCount: 0, preservedManual: 0, missingRaw: 0, enabledLocations: 2, items: [] }));
  };
  const Dialog = loadSource(new URL('../src/app/dashboard/vorschlaege/DiscoveryResetDialog.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Dialog, { initialMode: 'IGNORED', onClose() {}, onStarted() {} })); });
  await act(async () => { document.querySelector('input[value="RESCAN"]').click(); });
  assert.ok(requests.some(request => request.mode === 'RESCAN'));
  assert.ok(document.querySelector('[aria-label="Reset-Vorschau"]').textContent.includes('2 aktivierte Locations'));
  assert.equal(document.querySelector('input[type="checkbox"]').checked, false);
});

test('party standard genres submit display names, remain checked on edit and can be removed', async () => {
  const payloads = [];
  const save = async data => {
    payloads.push(data);
    const saved = { ...party, ...data, id: 'genre-party' };
    catalogue.parties = [saved];
    return saved;
  };
  catalogue = { parties: [], venues: [venue], actors: [], createParty: save, updateParty: save };
  const names = ['Emo', 'Metalcore', 'Hardcore'];
  globalThis.fetch = async url => new Response(JSON.stringify(url === '/api/v1/genres'
    ? names.map((name, index) => ({ id: `genre-id-${index}`, canonicalName: name.toLowerCase(), displayName: name, active: true })) : []));
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  click(button('Neu anlegen'));
  type(document.querySelector('[role="dialog"] input'), 'Genre Test');
  function genreCheckbox(name) {
    const label = [...document.querySelectorAll('[role="dialog"] label')].find(label => label.textContent.trim() === name);
    assert.ok(label, `Genre label ${name}`);
    return label.querySelector('input');
  }
  for (const name of names) click(genreCheckbox(name));
  await act(async () => { button('Übernehmen').click(); });
  assert.deepEqual([...payloads[0].standardGenres].sort(), [...names].sort());
  await act(async () => { button('Bearbeiten').click(); });
  for (const name of names) assert.equal(genreCheckbox(name).checked, true);
  click(genreCheckbox('Hardcore'));
  await act(async () => { button('Übernehmen').click(); });
  assert.deepEqual([...payloads[1].standardGenres].sort(), ['Emo', 'Metalcore']);
  await act(async () => { button('Bearbeiten').click(); });
  assert.equal(genreCheckbox('Hardcore').checked, false);
});

test('historical unavailable standard genres stay visible and can be explicitly removed', async () => {
  catalogue = { parties: [{ ...party, standardGenres: ['Retired Genre'] }], venues: [venue], actors: [] };
  globalThis.fetch = async () => new Response('[]');
  const Page = loadSource(new URL('../src/app/dashboard/portfolio/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  await act(async () => { button('Bearbeiten').click(); });
  const label = [...document.querySelectorAll('[role="dialog"] label')].find(label => label.textContent.includes('Retired Genre'));
  assert.ok(label);
  assert.equal(label.querySelector('input').checked, true);
  click(label.querySelector('input'));
  assert.equal(label.isConnected, false);
});

const monitoredBefore = { title: 'Teenage Dirtbag', starts: ['2026-11-07T23:00:00'], location: 'Blue Shell', state: '', evidence: 'Bisherige Quelle', url: 'https://club.example/old' };
const monitoredAfter = { ...monitoredBefore, title: 'Neon Paradise', url: 'https://club.example/new' };
const changeFixture = { id: 'change', revision: 0, kind: 'POSSIBLE_REPLACEMENT', state: 'OPEN', venue_name: 'Blue Shell', instagram: 'blue_shell', before_data: JSON.stringify(monitoredBefore), after_data: JSON.stringify(monitoredAfter), source_url: monitoredAfter.url, reason: 'Mehrere Veranstaltungen sind möglich', blocks_publication: true, calendar_entry_id: 'entry', calendar_party_id: 'party', calendar_venue_id: 'venue', calendar_date: '2026-11-07', calendar_start_time: '23:00', dm_text: '', answer: '', inquiry_status: 'NONE' };
function changeCatalogue() { catalogue = { parties: [party], venues: [venue], entries: [entry], reloadEntries: async () => {}, reloadDrafts: async () => {} }; }
async function mountChanges(fetcher) {
  changeCatalogue(); globalThis.fetch = fetcher;
  const Panel = loadSource(new URL('../src/app/dashboard/vorschlaege/EventChangesPanel.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Panel)); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
function jsonResponse(value, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }); }

test('event replacement comparison, editable DM and manual sent marker preserve focus and original entry', async () => {
  const calls = [];
  await mountChanges(async (url, options = {}) => {
    calls.push({ url, ...options });
    if (url.endsWith('/dm')) return jsonResponse({ text: 'Hallo Blue-Shell-Team! Teenage Dirtbag oder Neon Paradise am 07.11.?' });
    if (url.endsWith('/history')) return jsonResponse([]);
    if (options.method === 'PUT') return new Response(null, { status: 204 });
    return jsonResponse(url.endsWith('/cancellations') ? [] : [changeFixture]);
  });
  const trigger = button('Änderung vergleichen'); trigger.focus();
  await act(async () => { trigger.click(); });
  const table = document.querySelector('[role="dialog"] table');
  assert.match(table.textContent, /Teenage Dirtbag/); assert.match(table.textContent, /Neon Paradise/);
  for (const details of document.querySelectorAll('[role="dialog"] details')) details.open = true;
  const note = document.querySelector('[role="dialog"] textarea'); type(note, 'Location gefragt');
  await act(async () => { button('DM-Entwurf erstellen').click(); });
  const dm = [...document.querySelectorAll('label')].find(label => label.textContent.startsWith('DM-Entwurf')).querySelector('textarea');
  type(dm, ' Bitte bestätigen.');
  assert.equal(document.activeElement, dm);
  assert.ok(!calls.some(call => call.method === 'PUT'));
  await act(async () => { button('Rückfrage gesendet (manuell)').click(); });
  const decision = calls.find(call => call.method === 'PUT');
  assert.equal(JSON.parse(decision.body).action, 'SENT');
  assert.match(JSON.parse(decision.body).dmText, /Bitte bestätigen/);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
  assert.deepEqual(catalogue.entries, [entry]);
  assert.ok(calls.every(call => call.url.startsWith('/api/v1/')));
});

test('stale event-change decision displays conflict and retains unsaved input', async () => {
  await mountChanges(async (url, options = {}) => {
    if (options.method === 'PUT') return jsonResponse({ message: 'Änderung wurde inzwischen bearbeitet; neu laden' }, 409);
    return jsonResponse(url.endsWith('/history') || url.endsWith('/cancellations') ? [] : [changeFixture]);
  });
  await act(async () => { button('Änderung vergleichen').click(); });
  const input = document.querySelector('[role="dialog"] textarea'); type(input, 'Beide finden statt');
  await act(async () => { button('Als getrennte Veranstaltungen behandeln').click(); });
  assert.ok(document.querySelector('[role="dialog"]'));
  assert.equal(input.value, 'Beide finden statt');
  assert.match(document.querySelector('[role="dialog"] [role="alert"]').textContent, /inzwischen bearbeitet/);
  key('Escape');
});

test('manual cancellation uses central endpoint and the separate graphic draft never publishes', async () => {
  const calls = [];
  const cancellation = { id: 'cancellation', revision: 0, calendar_entry_id: 'entry', party_name: 'Test Party', venue_name: 'Test Venue', event_date: '2027-01-02', reason_code: 'PERSONALBEDINGTER_AUSFALL', announcement_caption: 'Die Veranstaltung wurde abgesagt.', announcement_status: 'DRAFT', media_asset_id: null };
  let confirmed = false;
  await mountChanges(async (url, options = {}) => {
    calls.push({ url, ...options });
    if (options.method === 'POST' && url.endsWith('/cancellations')) { confirmed = true; return jsonResponse({ id: cancellation.id }); }
    if (options.method === 'POST' && url.endsWith('/graphic')) return jsonResponse({ mediaAssetId: 'new-cancelled-image' });
    return jsonResponse(url.endsWith('/cancellations') ? (confirmed ? [cancellation] : []) : []);
  });
  click(button('Veranstaltung manuell absagen'));
  const selects = document.querySelectorAll('[role="dialog"] select');
  act(() => { selects[0].value = 'entry'; selects[0].dispatchEvent(new Event('change', { bubbles: true })); selects[1].value = 'PERSONALBEDINGTER_AUSFALL'; selects[1].dispatchEvent(new Event('change', { bubbles: true })); });
  await act(async () => { button('Gesamte Veranstaltung verbindlich absagen').click(); });
  const confirm = calls.find(call => call.method === 'POST' && call.url.endsWith('/cancellations'));
  assert.equal(JSON.parse(confirm.body).trigger, 'MANUAL');
  assert.equal(JSON.parse(confirm.body).reasonCode, 'PERSONALBEDINGTER_AUSFALL');
  for (const details of document.querySelectorAll('details')) details.open = true;
  click(button('Cancellation-Workflow öffnen'));
  await act(async () => { button('Absagegrafik vorbereiten').click(); });
  assert.match(document.querySelector('[role="dialog"]').textContent, /kein Remote-Abbruch bestätigt/);
  assert.ok(document.querySelector('a[href="/api/v1/cancellations/cancellation/graphic"]'));
  assert.ok(!calls.some(call => /publish|instagram/.test(call.url)));
  assert.deepEqual(catalogue.entries, [entry]);
  key('Escape');
});

test('editorial monitoring warnings preserve multiline captions and deliberately empty mentions across calendar refreshes', async () => {
  const saved = [];
  const persisted = { id: 'draft', calendarEntryId: 'entry', caption: 'Mein eigener Text\nBeginn: bleibt redaktionell', hashtags: '#original', mentionActorIds: [], genres: ['Emo'], status: 'DRAFT', musicTrackId: null, approvalNeedsReview: true, publicationBlockers: ['RESCHEDULE_DETECTED: Datum prüfen'] };
  catalogue = { parties: [{ ...party, residentDjIds: ['actor'], djMentionDefault: 'residents' }], venues: [venue], actors: [actor], entries: [{ ...entry, mentionDjIds: ['actor'] }], entriesLoading: false, entriesError: null, reloadEntries: async () => {}, drafts: [persisted], draftsLoading: false, draftsError: null, reloadDrafts: async () => {}, musicTracks: [], musicTracksLoading: false, musicTracksError: null, reloadMusicTracks: async () => {}, updateDraft: async value => { saved.push(value); return value; } };
  globalThis.fetch = async () => jsonResponse([genre]);
  const Page = loadSource(new URL('../src/app/dashboard/redaktion/page.tsx', import.meta.url)).default;
  await act(async () => { mount(h(Page)); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
  const caption = [...document.querySelectorAll('label')].find(label => label.querySelector('span')?.textContent === 'Caption').querySelector('textarea');
  assert.equal(caption.value, persisted.caption);
  const mention = [...document.querySelectorAll('label')].find(label => label.textContent.includes('Test DJ')).querySelector('input');
  assert.equal(mention.checked, false, 'Explicitly cleared mentions must not be repopulated from party defaults');
  assert.match(document.body.textContent, /Originalpost gesperrt/);
  catalogue = { ...catalogue, entries: [{ ...entry, date: '2027-01-03', mentionDjIds: ['actor'] }] };
  await act(async () => { root.render(h(StrictMode, null, h(Page))); });
  assert.equal(caption.value, persisted.caption, 'A calendar refresh must never regenerate a saved caption');
  type(caption, ' redaktionell ergänzt');
  await act(async () => { button('Entwurf speichern').click(); });
  assert.equal(saved.length, 1);
  assert.equal(saved[0].caption, persisted.caption + ' redaktionell ergänzt');
  assert.deepEqual(saved[0].mentionActorIds, []);
});
