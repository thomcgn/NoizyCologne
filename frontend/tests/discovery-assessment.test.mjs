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
const require = createRequire(import.meta.url);
const React = require('react');
const { act, createElement: h, StrictMode } = React;
const { createRoot } = require('react-dom/client');
const originalFetch = globalThis.fetch;
let poll;
window.setInterval = callback => { poll = callback; return globalThis.setInterval(callback, 10000); };
window.clearInterval = globalThis.clearInterval;
let root;
const calls = [];
let candidates;
let sourceRows=[];
let reloadedEntries;
const catalogue = {
  parties: [{ id: 'paradise', name: 'NEON PARADISE' }, { id: 'kult', name: 'NEON KULT' }],
  venues: [{ id: 'blue-shell', name: 'Blue Shell' }],
  reloadEntries: async () => { reloadedEntries++; },
  reloadParties: async () => {},
};
const modules = new Map();
function loadSource(url) {
  if (modules.has(url.href)) return modules.get(url.href);
  const loaded = { exports: {} };
  const compiled = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  function localRequire(specifier) {
    if (specifier.endsWith('/CatalogProvider')) return { useCatalog: () => catalogue };
    if (specifier.startsWith('.')) {
      for (const extension of ['.tsx', '.ts']) {
        const next = new URL(specifier + extension, url);
        try { readFileSync(next); } catch { continue; }
        return loadSource(next);
      }
    }
    return require(specifier);
  }
  new Function('require', 'module', 'exports', compiled)(localRequire, loaded, loaded.exports);
  modules.set(url.href, loaded.exports);
  return loaded.exports;
}
const Page = loadSource(new URL('../src/app/dashboard/vorschlaege/page.tsx', import.meta.url)).default;
const recognition = {
  status: 'COOPERATION',
  parties: [{ id: 'paradise', name: 'NEON PARADISE', matchedName: 'NEON PARADISE' }, { id: 'kult', name: 'NEON KULT', matchedName: 'NEON KULT' }],
};
function assessment(overrides = {}) {
  return { recognition, verification: { status: 'VERIFIED', reasons: ['Eindeutiges Datum der freigegebenen Location-Quelle'], date: '2026-11-07' }, lifecycle: 'ACTIVE', automaticImportEligible: false, importBlockers: [], ...overrides };
}
function candidate(overrides = {}) {
  return {
    id: 'candidate', title: 'NEON PARADISE VS. NEON KULT VOL: 2 THE REVENGE', venue_name: 'Blue Shell', instagram: 'blue_shell',
    source_url: 'https://www.blue-shell.de/events/neon-paradise-vs-neon-kult', event_date: '2026-11-07', party_id: null,
    status: 'NEEDS_REVIEW', reason: 'Kooperation erfordert manuelle Zuordnung', evidence: 'Blue Shell · 07.11.2026',
    inquiry_status: 'NONE', dm_text: '', calendar_entry_id: null, decision_origin: 'AUTOMATIC', matching_class: 'MATCH',
    automatic_reason: 'Zwei bekannte Reihen erkannt', matcher_version: 'party-rules-3', evaluated_at: null,
    previous_status: null, last_evaluation_id: null, assessment: assessment(), ...overrides,
  };
}
function overview() {
  return { candidates, sources: sourceRows, counts: candidates.map(item => ({ status: item.status, count: 1 })), runs: [], automaticallyImported: 0, operations: [], diagnoses: [] };
}
async function settle(work = () => {}) {
  await act(async () => { await work(); await new Promise(resolve => setTimeout(resolve, 20)); });
}
async function mount(item) {
  candidates = [item]; sourceRows=[]; calls.length = 0; reloadedEntries = 0;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, ...options });
    if (options.method === 'PUT' || options.method === 'POST') return new Response(null, { status: 204 });
    return new Response(JSON.stringify(overview()), { headers: { 'Content-Type': 'application/json' } });
  };
  const container = document.createElement('div'); document.body.append(container);
  root = createRoot(container);
  act(() => root.render(h(StrictMode, null, h(Page))));
  await settle();
}
function button(text, within = document) {
  const found = [...within.querySelectorAll('button')].find(element => element.textContent.trim().startsWith(text));
  assert.ok(found, `Button ${text} exists`);
  return found;
}
function dialog() {
  const found = document.querySelector('[role="dialog"]'); assert.ok(found); return found;
}
function control(label) {
  const found = [...dialog().querySelectorAll('label')].find(element => element.textContent.startsWith(label))?.querySelector('select, input');
  assert.ok(found, `Control ${label} exists`); return found;
}
function change(element, value) {
  act(() => {
    element.focus();
    const prototype = element.tagName === 'SELECT' ? dom.window.HTMLSelectElement.prototype : dom.window.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
  });
}
async function openReview() { await settle(() => button('Veranstaltung prüfen').click()); }
afterEach(() => {
  if (root) act(() => root.unmount());
  root = null; poll = null; document.body.replaceChildren(); globalThis.fetch = originalFetch;
});

test('cooperation shows both known series and requires an explicit single manual assignment', async () => {
  await mount(candidate());
  const card = document.querySelector('article');
  assert.match(card.textContent, /Blue Shell · 2026-11-07/);
  assert.match(card.textContent, /Kooperation erkannt/);
  assert.deepEqual([...card.querySelectorAll('li')].map(item => item.textContent), ['NEON PARADISE', 'NEON KULT']);
  await openReview();
  assert.equal(control('Partyreihe zuordnen').value, '', 'The first detected series must not become a hidden primary party');
  assert.equal(button('Manuell übernehmen', dialog()).disabled, true);
  assert.match(dialog().textContent, /Zuordnung muss manuell geprüft werden/);
  assert.match(dialog().textContent, /Terminprüfung: Termin belegt · 2026-11-07/);
  change(control('Partyreihe zuordnen'), 'kult');
  assert.equal(button('Manuell übernehmen', dialog()).disabled, false);
  await settle(() => button('Manuell übernehmen', dialog()).click());
  const review = calls.find(call => call.method === 'PUT');
  const imported = calls.find(call => call.method === 'POST');
  assert.equal(JSON.parse(review.body).partyId, 'kult');
  assert.deepEqual(JSON.parse(imported.body), { partyId: 'kult', date: '2026-11-07' });
  assert.equal(reloadedEntries, 1);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('READY is visible and benign UNCONFIRMED detail metadata does not become an absence or import blocker', async () => {
  const single = { status: 'SINGLE', parties: recognition.parties.slice(0, 1) };
  await mount(candidate({ title: 'NEON PARADISE Special', party_id: 'paradise', status: 'READY',
    reason: 'Bekannte Party; UNCONFIRMED Details nicht auswertbar', assessment: assessment({ recognition: single, automaticImportEligible: true }) }));
  await settle(() => button('Bereit zur Übernahme').click());
  assert.ok(calls.some(call => call.url.endsWith('?filter=READY')));
  assert.ok(document.querySelector('article'));
  await openReview();
  const details = dialog().querySelector('section');
  assert.match(details.textContent, /Terminprüfung: Termin belegt/);
  assert.match(details.textContent, /Veranstaltungsstatus: Kein Absage- oder Verschiebungshinweis/);
  assert.match(details.textContent, /Automatische Übernahme: Voraussetzungen erfüllt/);
  assert.doesNotMatch(details.textContent, /Absage erkannt|Übernahme gesperrt/);
  assert.equal(button('Manuell übernehmen', dialog()).disabled, false);
});

for (const [status, label] of [['MISSING_DATA', 'Angaben fehlen'], ['CONFLICTING_DATA', 'Widersprüchliche Angaben'], ['SPECULATIVE', 'Termin nicht ausreichend belegt']]) {
  test(`${status} remains visible for manual verification and allows a corrected date without a hard blocker`, async () => {
    await mount(candidate({ party_id: 'paradise', event_date: null,
      assessment: assessment({ verification: { status, reasons: ['Datum bitte anhand der Quelle bestätigen'], date: null } }) }));
    await openReview();
    assert.match(dialog().querySelector('section').textContent, new RegExp(label));
    assert.equal(button('Manuell übernehmen', dialog()).disabled, true);
    change(control('Korrigiertes / bestätigtes Datum'), '2026-11-07');
    assert.equal(button('Manuell übernehmen', dialog()).disabled, false, 'Automatic ineligibility must not replace manual verification');
  });
}

for (const [lifecycle, label] of [['CANCELLED', 'Absage erkannt'], ['POSTPONED', 'Veranstaltung verschoben'], ['RESCHEDULED', 'Veranstaltung auf einen anderen Termin verlegt']]) {
  test(`${lifecycle} displays the server blocker and prevents manual import despite selected party and date`, async () => {
    await mount(candidate({ party_id: 'paradise', assessment: assessment({ lifecycle, importBlockers: ['Zuerst im bestehenden Änderungs- und Absageworkflow klären'] }) }));
    await openReview();
    assert.match(dialog().querySelector('section').textContent, new RegExp(label));
    assert.match(dialog().textContent, /Übernahme gesperrt/);
    const imported = button('Manuell übernehmen', dialog());
    assert.equal(imported.disabled, true);
    await settle(() => imported.click());
    assert.ok(!calls.some(call => call.method === 'POST' || call.method === 'PUT'));
  });
}

test('refresh updates lifecycle blockers while retaining saved and unsaved manual party/date choices and focus', async () => {
  await mount(candidate({ party_id: 'kult', event_date: '2026-11-08', decision_origin: 'MANUAL' }));
  await openReview();
  assert.equal(control('Partyreihe zuordnen').value, 'kult');
  assert.equal(control('Korrigiertes / bestätigtes Datum').value, '2026-11-08');
  change(control('Partyreihe zuordnen'), 'paradise');
  const date = control('Korrigiertes / bestätigtes Datum'); change(date, '2026-11-09');
  candidates = [candidate({ party_id: 'kult', event_date: '2026-11-08', decision_origin: 'MANUAL',
    assessment: assessment({ lifecycle: 'CANCELLED', importBlockers: ['Neu erkannte Absage zuerst klären'] }) })];
  assert.ok(poll);
  await settle(() => poll());
  assert.match(dialog().querySelector('section').textContent, /Absage erkannt/);
  assert.equal(control('Partyreihe zuordnen').value, 'paradise');
  assert.equal(date.value, '2026-11-09');
  assert.equal(document.activeElement, date);
  assert.equal(button('Manuell übernehmen', dialog()).disabled, true);
  await settle(() => button('Speichern', dialog()).click());
  const review = calls.find(call => call.method === 'PUT');
  assert.equal(JSON.parse(review.body).partyId, 'paradise');
  assert.equal(JSON.parse(review.body).date, '2026-11-09');
});

test('portfolio approval uses the threshold of all active project genres and explicit confirmation',async()=>{
  await mount(candidate({title:'New Alternative Party',matching_class:'POSSIBLE_MATCH',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{matched:2,total:4,percent:50,names:['Emo','Indie'],eligible:true}}));
  await openReview();assert.match(dialog().textContent,/2 von 4 aktiven Projektgenres \(50 %\)/);
  const name=control('Name im Portfolio');change(name,'New Alternative Night');assert.equal(document.activeElement,name);
  await settle(()=>button('Ins Portfolio aufnehmen',dialog()).click());
  const request=calls.find(call=>call.url.endsWith('/portfolio')&&call.method==='POST');
  assert.deepEqual(JSON.parse(request.body),{name:'New Alternative Night'});
  assert.equal(document.querySelector('[role="dialog"]'),null);assert.ok(!calls.some(call=>/instagram|publish|cancellations/.test(call.url)));
});
test('below fifty percent no new portfolio series is offered even when an individual genre matches',async()=>{
  await mount(candidate({title:'Unknown Emo Party',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{matched:1,total:4,percent:25,names:['Emo'],eligible:false}}));
  await openReview();assert.match(dialog().textContent,/1 von 4/);
  assert.ok(![...dialog().querySelectorAll('button')].some(item=>item.textContent==='Ins Portfolio aufnehmen'));
});
test('partial MTC source is not an empty calendar and new source options retain focus',async()=>{
  await mount(candidate());
  sourceRows=[{id:'mtc-source',venue_id:'blue-shell',venue_name:'MTC Cologne',url:'https://www.mtc-cologne.de/about',source_type:'OFFICIAL',source_kind:'WEBSITE',approved:true,status:'BROWSER_PARTIAL',quality:'PARTIAL',event_count:0,enabled:true,priority:5,fallback_used:false,fetch_method:'BROWSER',item_selector:'',title_selector:'',date_selector:'',fetch_diagnostics:'ROBOTS_BLOCKED; JAVASCRIPT_ERROR',last_fetched_at:'2026-11-06T09:00:00Z',last_success_at:null}];
  await settle(()=>poll());assert.match(document.body.textContent,/Nur teilweise ausgewertet/);assert.ok(!document.body.textContent.includes('Ausdrücklich keine Veranstaltungen gemeldet'));
  const technical=[...document.querySelectorAll('details')].find(item=>item.querySelector('summary')?.textContent==='Technische Details');assert.equal(technical.open,false);
  await settle(()=>button('Quelle / Profil bearbeiten').click());
  change(control('Quellenart'),'OFFICIAL_TICKETS');change(control('Reihenfolge'),'7');
  const url=control('Quell-URL');change(url,'https://tickets.example/mtc');assert.equal(document.activeElement,url);
  await settle(()=>button('Speichern',dialog()).click());
  const body=JSON.parse(calls.find(call=>call.url.endsWith('/sources')&&call.method==='POST').body);
  assert.equal(body.sourceKind,'OFFICIAL_TICKETS');assert.equal(body.priority,7);assert.equal(body.enabled,true);assert.equal(body.approved,true);
});
test('source comparison displays title, date and location without raw JSON',async()=>{
  await mount(candidate());await openReview();const previous=globalThis.fetch;
  globalThis.fetch=async(url,options={})=>url.endsWith('/sources')?new Response(JSON.stringify([{url:'https://tickets.example/event',source_kind:'OFFICIAL_TICKETS',event_data:JSON.stringify({title:'NEON PARADISE',starts:['2026-11-07T23:00'],location:'Blue Shell',evidence:'Emo and Indie DJs'})}]),{headers:{'Content-Type':'application/json'}}):previous(url,options);
  await settle(()=>button('Quellenbelege laden',dialog()).click());
  assert.match(dialog().textContent,/Offizielle Ticketseite/);assert.match(dialog().textContent,/2026-11-07T23:00/);assert.ok(!dialog().textContent.includes('"starts":'));
});
