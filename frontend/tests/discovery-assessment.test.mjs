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
window.confirm = () => true;
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
let reloadedParties;
let reloadedDrafts;
const catalogue = {
  parties: [{ id: 'paradise', name: 'NEON PARADISE' }, { id: 'kult', name: 'NEON KULT' }],
  venues: [{ id: 'blue-shell', name: 'Blue Shell' }],
  reloadEntries: async () => { reloadedEntries++; },
  reloadParties: async () => { reloadedParties++; },
  reloadDrafts: async () => { reloadedDrafts++; },
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
async function mount(item, failInitial = false) {
  candidates = Array.isArray(item) ? item : [item]; sourceRows=[]; calls.length = 0; reloadedEntries = 0; reloadedParties = 0; reloadedDrafts = 0;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, ...options });
    if (failInitial) return new Response(JSON.stringify({message: 'Quelle nicht erreichbar'}), {status: 503});
    if (url.endsWith('/confirm') && options.method === 'POST') {
      const payload=JSON.parse(options.body);
      candidates=candidates.map(item => ({...item,status:'IMPORTED',calendar_entry_id:'imported-entry',party_id:payload.partyId,event_date:payload.date}));
      return new Response(JSON.stringify({calendarEntryId:'imported-entry'}));
    }
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
async function openReview() { await settle(() => { const trigger = [...document.querySelectorAll('[data-record-id] button')].find(item => /Veranstaltung prüfen|Ins Portfolio übernehmen|Übernahme öffnen/.test(item.textContent)); assert.ok(trigger); trigger.click(); }); }
afterEach(() => {
  if (root) act(() => root.unmount());
  root = null; poll = null; document.body.replaceChildren(); window.localStorage.clear(); window.confirm = () => true; globalThis.fetch = originalFetch;
});

test('cooperation shows both known series and requires an explicit single manual assignment', async () => {
  await mount(candidate());
  const card = document.querySelector('[data-record-id]');
  assert.match(card.textContent, /Blue Shell07. November 2026/);
  assert.match(card.textContent, /Kooperation erkannt/);
  assert.deepEqual([...card.querySelectorAll('li')].map(item => item.textContent), ['NEON PARADISE', 'NEON KULT']);
  await openReview();
  assert.equal(control('Partyreihe zuordnen').value, '', 'The first detected series must not become a hidden primary party');
  assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
  assert.match(dialog().textContent, /Zuordnung muss manuell geprüft werden/);
  assert.match(dialog().textContent, /Terminprüfung: Termin belegt · 2026-11-07/);
  change(control('Partyreihe zuordnen'), 'kult');
  assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, false);
  await settle(() => button('Bestätigen & übernehmen', dialog()).click());
  const review = calls.find(call => call.method === 'PUT');
  const imported = calls.find(call => call.method === 'POST');
  assert.equal(review, undefined, 'Confirmation must not save separately before importing');
  assert.equal(imported.url, '/api/v1/discovery/candidates/candidate/confirm');
  assert.deepEqual(JSON.parse(imported.body), { partyId: 'kult', date: '2026-11-07', inquiryStatus:'NONE', dmText:'', seriesAlias:null });
  assert.equal(reloadedParties, 1); assert.equal(reloadedDrafts, 1);
  assert.equal(document.querySelectorAll('[data-record-id]').length, 0);
  assert.equal(reloadedEntries, 1);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('READY is visible and benign UNCONFIRMED detail metadata does not become an absence or import blocker', async () => {
  const single = { status: 'SINGLE', parties: recognition.parties.slice(0, 1) };
  await mount(candidate({ title: 'NEON PARADISE Special', party_id: 'paradise', status: 'READY',
    reason: 'Bekannte Party; UNCONFIRMED Details nicht auswertbar', assessment: assessment({ recognition: single, automaticImportEligible: true }) }));
  assert.equal(document.querySelectorAll('[data-record-id]').length, 0, 'Known ready parties do not become unnecessary review cases');
  await settle(() => document.querySelector('.discovery-history > summary').click());
  assert.ok(calls.some(call => call.url.endsWith('?filter=READY')));
  assert.ok(document.querySelector('[data-record-id]'));
  await openReview();
  const details = dialog().querySelector('section');
  assert.match(details.textContent, /Terminprüfung: Termin belegt/);
  assert.match(details.textContent, /Veranstaltungsstatus: Kein Absage- oder Verschiebungshinweis/);
  assert.match(details.textContent, /Automatische Übernahme: Voraussetzungen erfüllt/);
  assert.doesNotMatch(details.textContent, /Absage erkannt|Übernahme gesperrt/);
  assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, false);
});

for (const [status, label] of [['MISSING_DATA', 'Angaben fehlen'], ['CONFLICTING_DATA', 'Widersprüchliche Angaben'], ['SPECULATIVE', 'Termin nicht ausreichend belegt']]) {
  test(`${status} remains visible for manual verification and allows a corrected date without a hard blocker`, async () => {
    await mount(candidate({ party_id: 'paradise', event_date: null,
      assessment: assessment({ verification: { status, reasons: ['Datum bitte anhand der Quelle bestätigen'], date: null } }) }));
    await openReview();
    assert.match(dialog().querySelector('section').textContent, new RegExp(label));
    assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
    change(control('Korrigiertes / bestätigtes Datum'), '2026-11-07');
    assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, false, 'Automatic ineligibility must not replace manual verification');
  });
}

for (const [lifecycle, label] of [['CANCELLED', 'Absage erkannt'], ['POSTPONED', 'Veranstaltung verschoben'], ['RESCHEDULED', 'Veranstaltung auf einen anderen Termin verlegt']]) {
  test(`${lifecycle} displays the server blocker and prevents manual import despite selected party and date`, async () => {
    await mount(candidate({ party_id: 'paradise', assessment: assessment({ lifecycle, importBlockers: ['Zuerst im bestehenden Änderungs- und Absageworkflow klären'] }) }));
    await openReview();
    assert.match(dialog().querySelector('section').textContent, new RegExp(label));
    assert.match(dialog().textContent, /Übernahme gesperrt/);
    const imported = button('Bestätigen & übernehmen', dialog());
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
  assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
  await settle(() => button('Speichern', dialog()).click());
  const review = calls.find(call => call.method === 'PUT');
  assert.equal(JSON.parse(review.body).partyId, 'paradise');
  assert.equal(JSON.parse(review.body).date, '2026-11-09');
});

test('portfolio approval compares recognized event genres with the active portfolio and requires confirmation',async()=>{
  await mount(candidate({title:'New Alternative Party',matching_class:'POSSIBLE_MATCH',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{matched:2,total:4,percent:50,names:['Emo','Indie'],eligible:true}}));
  await openReview();assert.match(dialog().textContent,/2 von 4 erkannten Eventgenres.*\(50 %\)/);
  const name=control('Name im Portfolio');change(name,'New Alternative Night');assert.equal(document.activeElement,name);
  await settle(()=>button('Ins Portfolio übernehmen',dialog()).click());
  const request=calls.find(call=>call.url.endsWith('/portfolio')&&call.method==='POST');
  assert.deepEqual(JSON.parse(request.body),{name:'New Alternative Night',seriesAlias:null});
  assert.equal(document.querySelector('[role="dialog"]'),null);assert.ok(!calls.some(call=>/instagram|publish|cancellations/.test(call.url)));
  assert.equal(reloadedEntries, 1); assert.equal(reloadedParties, 1); assert.equal(reloadedDrafts, 1);
});
test('below fifty percent no new portfolio series is offered even when an individual genre matches',async()=>{
  await mount(candidate({title:'Unknown Emo Party',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{matched:1,total:4,percent:25,names:['Emo'],eligible:false}}));
  await openReview();assert.match(dialog().textContent,/1 von 4/);
  assert.ok(![...dialog().querySelectorAll('button')].some(item=>item.textContent==='Ins Portfolio übernehmen'));
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

test('Discovery separates eligible new series, protected decisions and imported history without debug cards', async () => {
  const unknown = candidate({ id: 'new', title: 'Neue Indie-Nacht', assessment: assessment({ recognition: {status: 'NONE', parties: []} }), genreCoverage: {matched: 2, total: 4, percent: 50, names: ['Emo', 'Indie'], eligible: true} });
  await mount([unknown, candidate({id: 'protected', title: 'Manuelle Rückfrage', inquiry_status: 'SENT', decision_origin: 'MANUAL'}), candidate({id: 'imported', title: 'Historischer Termin', status: 'IMPORTED', calendar_entry_id: 'entry'})]);
  const fresh = document.querySelector('section[aria-label="Neue Partyreihen"]');
  const review = document.querySelector('section[aria-label="Prüfbedarf"]');
  assert.equal(fresh.querySelectorAll('[data-record-id]').length, 1);
  assert.match(fresh.textContent, /Neue Indie-Nacht/);
  assert.match(fresh.textContent, /50 %/);
  assert.match(fresh.textContent, /EmoIndie/);
  assert.equal(review.querySelectorAll('[data-record-id]').length, 1);
  assert.match(review.textContent, /Rückfrage ist noch offen/);
  assert.doesNotMatch(document.querySelector('main').textContent, /Historischer Termin|party-rules-3|POSSIBLE_MATCH/);
  assert.equal(document.querySelector('.discovery-history').open, false);
  assert.ok(!calls.some(call => call.url.endsWith('?filter=IMPORTED')));
});

test('empty Discovery has explicit empty states and keeps the existing scan endpoint', async () => {
  await mount([]);
  assert.match(document.querySelector('h1').textContent, /^Discovery$/);
  assert.match(document.body.textContent, /Keine neuen passenden Partyreihen/);
  assert.match(document.body.textContent, /Keine offenen Ausnahmefälle/);
  await settle(() => button('Jetzt suchen').click());
  assert.equal(calls.filter(call => call.method === 'POST' && call.url.endsWith('/scan')).length, 1);
});

test('long review lists reveal another page without mounting the historical archive', async () => {
  await mount(Array.from({length: 35}, (_, index) => candidate({id: `review-${index}`})));
  assert.equal(document.querySelectorAll('[data-record-id]').length, 25);
  await settle(() => button('Weiter').click());
  assert.equal(document.querySelectorAll('[data-record-id]').length, 10);
  assert.ok(!calls.some(call => call.url.endsWith('?filter=ARCHIVE')));
});

test('portfolio confirmation prevents duplicate submissions and removes the approved series after refresh', async () => {
  const fresh = candidate({ title: 'Neue Reihe', assessment: assessment({recognition: {status: 'NONE', parties: []}}), genreCoverage: {matched: 2, total: 4, percent: 50, names: ['Emo', 'Indie'], eligible: true} });
  await mount(fresh);
  await openReview();
  assert.ok(!calls.some(call => call.method === 'POST'));
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/portfolio') && options.method === 'POST') {
      candidates = [candidate({...fresh, status: 'IMPORTED', calendar_entry_id: 'entry', party_id: 'approved'})];
      await new Promise(resolve => setTimeout(resolve, 5));
    }
    return previous(url, options);
  };
  const confirm = button('Ins Portfolio übernehmen', dialog());
  await settle(() => { confirm.click(); confirm.click(); });
  assert.equal(calls.filter(call => call.url.endsWith('/portfolio') && call.method === 'POST').length, 1);
  assert.equal(document.querySelectorAll('[data-record-id]').length, 0);
  assert.equal(reloadedEntries, 1); assert.equal(reloadedDrafts, 1);
});

test('failed initial requests do not claim that Discovery is empty or up to date', async () => {
  await mount([], true);
  assert.match(document.querySelector('[role="alert"]').textContent, /Quelle nicht erreichbar/);
  assert.match(document.body.textContent, /Ergebnisse derzeit nicht verfügbar/);
  assert.doesNotMatch(document.body.textContent, /Keine neuen passenden Partyreihen|Keine offenen Ausnahmefälle/);
});


test('Discovery search, sorting, genre filter and persisted card view preserve the candidate set', async () => {
  await mount([
    candidate({id: 'z', title: 'Zulu', genreCoverage: {percent: 50, matched: 1, total: 2, names: ['Indie']}}),
    candidate({id: 'a', title: 'Alpha', venue_name: 'Andere Location', genreCoverage: {percent: 100, matched: 2, total: 2, names: ['Indie', 'Emo']}}),
  ]);
  const section = document.querySelector('section[aria-label="Prüfbedarf"]');
  const ids = () => [...section.querySelectorAll('[data-record-id]')].map(row => row.dataset.recordId);
  assert.deepEqual(ids(), ['a', 'z']);
  await settle(() => section.querySelector('th button').click());
  assert.deepEqual(ids(), ['z', 'a']);
  const search = section.querySelector('input[type="search"]');
  change(search, 'Andere Location');
  assert.deepEqual(ids(), ['a']);
  assert.equal(document.activeElement, search);
  change(search, 'unbekannt');
  assert.match(section.textContent, /Keine Treffer für diese Auswahl/);
  change(search, '');
  change(section.querySelector('select'), '75');
  assert.deepEqual(ids(), ['a']);
  await settle(() => button('Karten', section).click());
  assert.equal(section.querySelectorAll('article').length, 1);
  assert.equal(window.localStorage.getItem('noizy-view-discovery-review'), 'cards');
  await settle(() => { root.unmount(); root = null; });
  await mount(candidate());
  assert.ok(document.querySelector('section[aria-label="Prüfbedarf"] article'));
  assert.equal(calls.filter(call => call.method === 'PUT' || call.method === 'POST').length, 0);
});

test('Discovery drawer protects unsaved input on Escape and close, then restores its trigger', async () => {
  await mount(candidate());
  const trigger = document.querySelector('[data-record-open]');
  trigger.focus();
  await settle(() => trigger.click());
  assert.ok(dialog().classList.contains('cc-drawer'));
  change(dialog().querySelector('input[type="date"]'), '2026-11-14');
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  await settle(() => document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true})));
  assert.equal(dialog().querySelector('input[type="date"]').value, '2026-11-14');
  await settle(() => dialog().querySelector('[data-modal-close]').click());
  assert.equal(prompts, 2);
  const unload = new Event('beforeunload', {cancelable: true});
  window.dispatchEvent(unload);
  assert.equal(unload.defaultPrevented, true);
  window.confirm = () => true;
  await settle(() => dialog().querySelector('[data-modal-close]').click());
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
  assert.equal(calls.filter(call => call.method === 'PUT' || call.method === 'POST').length, 0);
});


test('saving a manual assignment never imports and an import conflict preserves the form without success notice', async () => {
  await mount(candidate({party_id:'kult'})); await openReview();
  await settle(() => button('Speichern',dialog()).click());
  assert.equal(calls.filter(call=>call.method==='PUT').length,1);
  assert.equal(calls.filter(call=>call.method==='POST').length,0);
  assert.match(document.body.textContent,/Bearbeitungsstand gespeichert. Kein Kalenderimport/);
  await openReview(); change(control('Korrigiertes / bestätigtes Datum'),'2026-11-08');
  const previous=globalThis.fetch;
  globalThis.fetch=async(url,options={})=>url.endsWith('/confirm') ? new Response(JSON.stringify({message:'Ganztägige Location-Sperre am bestätigten Datum'}),{status:409}) : previous(url,options);
  await settle(()=>button('Bestätigen & übernehmen',dialog()).click());
  assert.match(dialog().textContent,/Ganztägige Location-Sperre/);
  assert.equal(control('Korrigiertes / bestätigtes Datum').value,'2026-11-08');
  assert.equal(reloadedEntries,0);assert.equal(reloadedDrafts,0);
  assert.doesNotMatch(document.body.textContent,/Termin bestätigt und in den Kalender übernommen/);
});

test('confirmation includes only an explicitly checked reusable series alias and rejects missing import evidence', async () => {
  await mount(candidate({party_id:'kult'}));await openReview();
  const remember=dialog().querySelector('input[type="checkbox"]');await settle(()=>remember.click());
  change(control('Wiederverwendbarer Reihenname'),'NEON KULT');
  let submitted;const previous=globalThis.fetch;
  globalThis.fetch=async(url,options={})=>{
    if(url.endsWith('/confirm')) {submitted=JSON.parse(options.body);return new Response(null,{status:204});}
    return previous(url,options);
  };
  await settle(()=>button('Bestätigen & übernehmen',dialog()).click());
  assert.equal(submitted.seriesAlias,'NEON KULT');
  assert.match(dialog().textContent,/Kalenderimport wurde nicht bestätigt/);
  assert.equal(reloadedEntries,0);assert.equal(reloadedDrafts,0);
});


test('known series display source and standard genres separately without a misleading zero-of-portfolio label', async () => {
  await mount(candidate({party_id:'kult',assessment:assessment({recognition:{status:'SINGLE',parties:recognition.parties.slice(1)}}),genreCoverage:{matched:1,total:1,percent:100,names:['Emo'],eligible:true,sourceNames:[],standardNames:['Emo'],basis:'STANDARD'}}));
  const row=document.querySelector('[data-record-id]');assert.match(row.textContent,/Standardgenres · 100 %/);
  await openReview();const details=dialog().querySelector('[aria-label="Genregrundlage"]');
  assert.match(details.textContent,/Genres aus Veranstaltungsquelle: Keine Eventgenres belegt/);
  assert.match(details.textContent,/Standardgenres der bekannten Partyreihe: Emo/);
  assert.doesNotMatch(details.textContent,/0 von|aktiven Projektgenres/);
});

test('the review shows the standards of the locally selected party instead of a stale API assignment', async () => {
  const previous=catalogue.parties;
  catalogue.parties=previous.map(item=>({...item,standardGenres:item.id==='kult'?['Emo']:['Rock']}));
  try {
    await mount(candidate({party_id:'kult',genreCoverage:{matched:1,total:1,percent:100,names:['Emo'],eligible:true,sourceNames:[],standardNames:['Emo'],basis:'STANDARD'}}));
    await openReview();change(control('Partyreihe zuordnen'),'paradise');
    assert.match(dialog().querySelector('[aria-label="Genregrundlage"]').textContent,/Standardgenres der bekannten Partyreihe: Rock/);
  } finally {catalogue.parties=previous;}
});

test('instant rejection saves only a durable decision without opening or overwriting the editor', async () => {
  await mount(candidate({inquiry_status:'SENT',dm_text:'Geprüfter Text'}));
  const fallback=globalThis.fetch;
  globalThis.fetch=async (url,options={})=>{
    if(url.endsWith('/reject')) {
      calls.push({url,...options});
      candidates=candidates.map(item=>({...item,status:'REJECTED',decision_origin:'MANUAL'}));
      return new Response(null,{status:204});
    }
    return fallback(url,options);
  };
  const reject=button('Sofort ablehnen');
  await settle(()=>{reject.click();reject.click();});
  assert.equal(calls.filter(call=>call.url.endsWith('/reject')).length,1);
  assert.equal(document.querySelector('[role="dialog"]'),null);
  assert.equal(document.querySelectorAll('[data-record-id]').length,0);
  assert.equal(calls.some(call=>call.method==='PUT'||call.url.endsWith('/confirm')),false);
  assert.equal(candidates[0].dm_text,'Geprüfter Text');
  assert.match(document.body.textContent,/manuelle Entscheidung bleibt/);
  await settle(()=>poll());
  assert.equal(document.querySelectorAll('[data-record-id]').length,0);
});

test('failed instant rejection keeps the proposal visible with an actionable error', async () => {
  await mount(candidate());
  globalThis.fetch=async()=>new Response(JSON.stringify({message:'Bereits übernommen'}),{status:409});
  await settle(()=>button('Sofort ablehnen').click());
  assert.ok(document.querySelector('[data-record-id]'));
  assert.match(document.querySelector('[role="alert"]').textContent,/Bereits übernommen/);
  assert.equal(button('Sofort ablehnen').disabled,false);
});

test('special-source proposals require an explicit venue without a default and confirm it atomically', async () => {
  await mount(candidate({special_source:true,venue_id:null,venue_name:'Veranstaltungsort offen',reported_location:'Blue Shell'}));
  await openReview();
  assert.equal(control('Veranstaltungsort zuordnen').value,'');
  change(control('Partyreihe zuordnen'),'kult');
  assert.equal(button('Bestätigen & übernehmen',dialog()).disabled,true);
  change(control('Veranstaltungsort zuordnen'),'blue-shell');
  await settle(()=>button('Bestätigen & übernehmen',dialog()).click());
  const confirmed=calls.find(call=>call.url.endsWith('/confirm'));
  assert.equal(JSON.parse(confirmed.body).venueId,'blue-shell');
  assert.equal(calls.some(call=>call.method==='PUT'),false);
});

test('special sources can be added without a location and removed without deleting proposals', async () => {
  await mount(candidate());
  await settle(()=>document.querySelector('.discovery-tools > summary').click());
  await settle(()=>button('Sonderquelle hinzufügen').click());
  assert.match(dialog().textContent,/keine Location/);
  assert.equal([...dialog().querySelectorAll('label')].some(item=>item.textContent.startsWith('Location')),false);
  change(control('Name der Sonderquelle'),'QRUSH');change(control('Quell-URL'),'https://qrushapp.de/');
  const fallback=globalThis.fetch;
  globalThis.fetch=async(url,options={})=>{
    if(url==='/api/v1/discovery/special-sources'&&options.method==='POST') {
      calls.push({url,...options});
      sourceRows=[{id:'qrush',name:'QRUSH',venue_id:null,venue_name:'',special_source:true,url:'https://qrushapp.de/',source_type:'EXTERNAL',source_kind:'PLATFORM',approved:false,status:'NOT_CHECKED'}];
      return new Response(JSON.stringify({id:'qrush'}));
    }
    if(options.method==='DELETE') {calls.push({url,...options});sourceRows=[];return new Response(null,{status:204});}
    return fallback(url,options);
  };
  await settle(()=>dialog().querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(document.querySelector('[role="dialog"]'),null);
  const added=calls.find(call=>call.method==='POST');
  assert.deepEqual(JSON.parse(added.body),{name:'QRUSH',url:'https://qrushapp.de/',itemSelector:'',titleSelector:'',dateSelector:''});
  assert.match(document.querySelector('[aria-label="Sonderquellen"]').textContent,/QRUSH/);
  await settle(()=>button('Sonderquelle löschen').click());
  assert.ok(calls.some(call=>call.method==='DELETE'&&call.url==='/api/v1/discovery/special-sources/qrush'));
  assert.match(document.querySelector('[aria-label="Sonderquellen"]').textContent,/Noch keine Sonderquellen/);
  assert.equal(candidates.length,1);
});

test('failed special-source addition preserves the entered configuration', async () => {
  await mount(candidate());
  await settle(()=>document.querySelector('.discovery-tools > summary').click());
  await settle(()=>button('Sonderquelle hinzufügen').click());
  change(control('Name der Sonderquelle'),'QRUSH');change(control('Quell-URL'),'https://qrushapp.de/');
  globalThis.fetch=async()=>new Response(JSON.stringify({message:'Diese Sonderquelle ist bereits vorhanden'}),{status:409});
  await settle(()=>dialog().querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(control('Quell-URL').value,'https://qrushapp.de/');
  assert.match(dialog().textContent,/bereits vorhanden/);
});
