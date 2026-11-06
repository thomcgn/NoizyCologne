import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import ts from 'typescript';

const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost'});
for (const name of ['window', 'document', 'HTMLElement', 'HTMLDetailsElement', 'Event', 'KeyboardEvent', 'StorageEvent']) globalThis[name] = dom.window[name];
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const require = createRequire(import.meta.url);
const React = require('react');
const {act, createElement: h, StrictMode} = React;
const {createRoot} = require('react-dom/client');
const {renderToString} = require('react-dom/server');
let modules;
let root;
const originalSetItem = dom.window.Storage.prototype.setItem;

function loadSource(url) {
  if (modules.has(url.href)) return modules.get(url.href);
  const loaded = {exports: {}};
  const compiled = ts.transpileModule(readFileSync(url, 'utf8'), {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX}}).outputText;
  function localRequire(specifier) {
    if (specifier.startsWith('.')) {
      for (const extension of ['.tsx', '.ts']) {
        const next = new URL(specifier + extension, url);
        try {readFileSync(next);} catch {continue;}
        return loadSource(next);
      }
    }
    return require(specifier);
  }
  new Function('require', 'module', 'exports', compiled)(localRequire, loaded, loaded.exports);
  modules.set(url.href, loaded.exports);
  return loaded.exports;
}
function components() {
  const provider = loadSource(new URL('../src/app/ThemeProvider.tsx', import.meta.url));
  const Display = loadSource(new URL('../src/app/dashboard/DisplayPreferences.tsx', import.meta.url)).default;
  const Toggle = loadSource(new URL('../src/app/ThemeToggle.tsx', import.meta.url)).default;
  function Probe() {const {theme, density} = provider.useTheme(); return h('output', null, `${theme}/${density}`);}
  return {provider, Display, Toggle, Probe};
}
function mount() {
  const {provider, Display, Toggle, Probe} = components();
  const container = document.createElement('div'); document.body.append(container);
  root = createRoot(container);
  act(() => root.render(h(StrictMode, null, h(provider.ThemeProvider, null, h(Display), h(Toggle), h(Probe)))));
}
function button(text) {const element = [...document.querySelectorAll('button')].find(element => element.textContent.trim() === text); assert.ok(element, text); return element;}
function select(label, value) {
  const fieldLabel = [...document.querySelectorAll('label')].find(element => element.textContent === label);
  const element = fieldLabel && document.getElementById(fieldLabel.htmlFor);
  assert.ok(element, label);
  act(() => {element.focus(); element.value = value; element.dispatchEvent(new Event('change', {bubbles: true}));});
  assert.equal(document.activeElement, element);
}
function open() {act(() => button('Darstellung').click());}
function selection() {return document.querySelector('output').textContent;}
beforeEach(() => {modules = new Map(); window.localStorage.clear(); delete document.documentElement.dataset.theme; delete document.documentElement.dataset.density;});
afterEach(() => {if (root) act(() => root.unmount()); root = null; document.body.replaceChildren(); dom.window.Storage.prototype.setItem = originalSetItem;});

test('new users receive graphite and compact; server rendering uses the same defaults', () => {
  const {provider, Probe} = components();
  assert.match(renderToString(h(provider.ThemeProvider, null, h(Probe))), /graphite\/compact/);
  mount();
  assert.equal(selection(), 'graphite/compact');
  assert.equal(document.documentElement.dataset.theme, 'graphite');
  assert.equal(document.documentElement.dataset.density, 'compact');
});

test('settings apply immediately, retain focus, persist and survive a remount', () => {
  mount(); open();
  select('Theme', 'light'); select('Informationsdichte', 'comfortable');
  assert.equal(selection(), 'light/comfortable');
  assert.equal(window.localStorage.getItem('noizy-theme'), 'light');
  assert.equal(window.localStorage.getItem('noizy-density'), 'comfortable');
  assert.match(document.querySelector('[role="status"]').textContent, /gespeichert/);
  act(() => root.unmount()); root = null; mount();
  assert.equal(selection(), 'light/comfortable');
});

for (const legacy of ['light', 'dark']) test(`existing ${legacy} selection remains and density stays independent`, () => {
  window.localStorage.setItem('noizy-theme', legacy);
  mount(); assert.equal(selection(), `${legacy === 'dark' ? 'midnight' : legacy}/compact`);
  open(); select('Informationsdichte', 'comfortable');
  assert.equal(selection(), `${legacy === 'dark' ? 'midnight' : legacy}/comfortable`);
});

test('all three themes are reachable through the existing quick toggle', () => {
  mount();
  for (const [name, expected] of [['Midnight', 'midnight'], ['Hell', 'light'], ['Graphit', 'graphite']]) {
    const toggle = document.querySelector(".theme-toggle");
    assert.ok(toggle.textContent.endsWith(name));
    act(() => toggle.click());
    assert.equal(selection(), `${expected}/compact`);
  }
});

test('storage events synchronize tabs and clearing or invalid values restore safe defaults', () => {
  mount();
  act(() => {
    window.localStorage.setItem('noizy-theme', 'light');
    window.localStorage.setItem('noizy-density', 'comfortable');
    for (const key of ['noizy-theme', 'noizy-density']) window.dispatchEvent(new StorageEvent('storage', {key, storageArea: window.localStorage}));
  });
  assert.equal(selection(), 'light/comfortable');
  act(() => {window.localStorage.clear(); window.dispatchEvent(new StorageEvent('storage', {key: null, storageArea: window.localStorage}));});
  assert.equal(selection(), 'graphite/compact');
  act(() => {window.localStorage.setItem('noizy-theme', 'invalid'); window.localStorage.setItem('noizy-density', 'invalid'); window.dispatchEvent(new StorageEvent('storage', {key: null, storageArea: window.localStorage}));});
  assert.equal(selection(), 'graphite/compact');
});

test('unavailable browser storage keeps a usable session selection and reports the limitation', () => {
  mount(); open();
  dom.window.Storage.prototype.setItem = () => {throw new Error('Storage disabled');};
  select('Theme', 'light'); select('Informationsdichte', 'comfortable');
  assert.equal(selection(), 'light/comfortable');
  assert.match(document.querySelector('[role="status"]').textContent, /bis zum Neuladen/);
  assert.equal(window.localStorage.getItem('noizy-theme'), null);
});

test('display dialog closes by Escape and returns focus to its trigger', () => {
  mount(); const trigger = button('Darstellung'); trigger.focus(); open();
  assert.ok(document.querySelector('[role="dialog"]'));
  act(() => document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true})));
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
});

test('bootstrap applies validated stored preferences before provider hydration and migrates legacy dark', () => {
  window.localStorage.setItem('noizy-theme', 'dark');
  window.localStorage.setItem('noizy-density', 'comfortable');
  const {preferenceBootstrap} = loadSource(new URL('../src/app/uiPreferences.ts', import.meta.url));
  new Function('localStorage', 'document', preferenceBootstrap)(window.localStorage, document);
  assert.equal(document.documentElement.dataset.theme, 'midnight');
  assert.equal(document.documentElement.dataset.density, 'comfortable');
  mount();
  assert.equal(document.documentElement.dataset.theme, 'midnight');
  assert.equal(selection(), 'midnight/comfortable');
});
