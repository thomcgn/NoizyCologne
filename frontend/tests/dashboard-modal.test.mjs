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
  test(`${name}: actual create/edit forms preserve input focus and restore trigger`, async () => {
    catalogue = { parties: [party], venues: [venue], actors: [actor], entries: [entry] };
    globalThis.ResizeObserver = class { observe() {} disconnect() {} };
    globalThis.fetch = async () => new Response(JSON.stringify([genre]), { status: 200, headers: { 'Content-Type': 'application/json' } });
    const Page = loadSource(new URL(`../src/app/dashboard/${file}`, import.meta.url)).default;
    await act(async () => { mount(h(Page, props)); });
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
      for (const theme of ['dark', 'light']) {
        for (const width of [390, 1280]) {
          document.documentElement.dataset.theme = theme;
          window.innerWidth = width;
          focusedField.focus();
          act(() => window.dispatchEvent(new Event('resize')));
          assert.equal(document.activeElement, focusedField);
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
