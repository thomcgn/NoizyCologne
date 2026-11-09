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
// jsdom has no layout/scroll implementation; configuration links still exercise their real click handler.
HTMLElement.prototype.scrollIntoView = () => {};
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
let geoRows=[];
let operationRows=[];
let genreRows=[];
let keywordRows=[];
let reloadedEntries;
let reloadedParties;
let reloadedDrafts;
const catalogue = {
  parties: [{ id: 'paradise', name: 'NEON PARADISE' }, { id: 'kult', name: 'NEON KULT' }],
  venues: [{ id: 'blue-shell', name: 'Blue Shell' }],
  reloadVenues: async () => {},
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
    if (specifier === "next/link") return {default: ({href, children, ...props}) => h("a", {href, ...props}, children)};
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
    venue_id: 'blue-shell', source_url: 'https://www.blue-shell.de/events/neon-paradise-vs-neon-kult', event_date: '2026-11-07', party_id: null,
    status: 'NEEDS_REVIEW', reason: 'Kooperation erfordert manuelle Zuordnung', evidence: 'Blue Shell · 07.11.2026',
    inquiry_status: 'NONE', dm_text: '', calendar_entry_id: null, decision_origin: 'AUTOMATIC', matching_class: 'MATCH',
    automatic_reason: 'Zwei bekannte Reihen erkannt', matcher_version: 'party-rules-3', evaluated_at: null,
    previous_status: null, last_evaluation_id: null, assessment: assessment(), ...overrides,
  };
}
function overview() {
  return { candidates, sources: sourceRows, counts: candidates.map(item => ({ status: item.status, count: 1 })), runs: [], automaticallyImported: 0, operations: operationRows, diagnoses: [] };
}
async function settle(work = () => {}) {
  await act(async () => { await work(); await new Promise(resolve => setTimeout(resolve, 20)); });
}
async function mount(item, failInitial = false) {
  candidates = Array.isArray(item) ? item : [item]; sourceRows=[]; geoRows=[]; operationRows=[]; genreRows=[{id:"emo",displayName:"Emo",active:true},{id:"indie",displayName:"Indie",active:true},{id:"metal",displayName:"Metalcore",active:true},{id:"retired",displayName:"Pop",active:false}]; calls.length = 0; reloadedEntries = 0; reloadedParties = 0; reloadedDrafts = 0;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, ...options });
    if(url==='/api/v1/discovery/keyword-rules' && (!options.method || options.method==='GET'))return new Response(JSON.stringify(keywordRows));
    if(url==='/api/v1/discovery/keyword-rules/batch' && options.method==='POST'){
      const body=JSON.parse(options.body);
      const existing=new Set(keywordRows.map(row=>row.keyword.toLowerCase()));
      let created=0,alreadyPresent=0;
      for(const keyword of body.keywords){
        if(existing.has(keyword.toLowerCase())){alreadyPresent++;continue;}
        keywordRows.push({id:`keyword-test-${keywordRows.length}`,keyword,reason:body.reason || `Automatischer Ausschluss: ${keyword}`,enabled:body.enabled});created++;
        existing.add(keyword.toLowerCase());
      }
      return new Response(JSON.stringify({created,alreadyPresent,skipped:[]}));
    }
    if(url.startsWith('/api/v1/discovery/keyword-rules') && ['POST','PUT'].includes(options.method)){
      const rule={id:'keyword-test',...JSON.parse(options.body)};keywordRows=options.method==='PUT' ? keywordRows.map(item=>item.id===url.split('/').pop() ? {...item,...rule,id:item.id} : item) : [...keywordRows,rule];
      return new Response(JSON.stringify({id:rule.id}));
    }
    if (url === '/api/v1/genres') return new Response(JSON.stringify(genreRows));
    if (url.endsWith('/geography') && !failInitial) return new Response(JSON.stringify({items:geoRows}));
    if (failInitial) return new Response(JSON.stringify({message: 'Quelle nicht erreichbar'}), {status: 503});
    if (url.endsWith('/portfolio') && options.method === 'POST') {
      candidates=candidates.map(item => ({...item,party_id:'approved',decision_origin:'MANUAL',assessment:assessment({recognition:{status:'SINGLE',parties:[{id:'approved',name:JSON.parse(options.body).name,matchedName:item.title}]}})}));
      return new Response(JSON.stringify({partyId:'approved'}));
    }
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
    const prototype = element.tagName === 'SELECT' ? dom.window.HTMLSelectElement.prototype
      : element.tagName === 'TEXTAREA' ? dom.window.HTMLTextAreaElement.prototype
      : dom.window.HTMLInputElement.prototype;
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
  assert.equal(dialog().querySelector('a[href="/dashboard/kalender?entry=imported-entry"]')?.textContent, 'Im Kalender anzeigen');
  assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
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

test('portfolio approval saves chosen standards and keeps the dialog open with the new series assigned',async()=>{
  await mount(candidate({title:'New Alternative Party',matching_class:'POSSIBLE_MATCH',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{matched:2,total:4,percent:50,names:['Emo','Indie'],eligible:true,suggestedStandardGenres:['Emo','Pop','Unknown']}}));
  await openReview();assert.match(dialog().textContent,/2 von 4 erkannten Eventgenres.*\(50 %\)/);
  assert.equal(button('Ins Portfolio übernehmen',dialog()).disabled,true);
  change(control('Standard-Startuhrzeit'),'23:00');
  const name=control('Name im Portfolio');change(name,'New Alternative Night');assert.equal(document.activeElement,name);
  const selector=[...dialog().querySelectorAll('fieldset')].find(item=>item.querySelector('legend')?.textContent==='Standardgenres');
  const checkbox=name=>[...selector.querySelectorAll('label')].find(label=>label.textContent===name)?.querySelector('input');
  assert.equal(checkbox('Emo').checked,true);assert.equal(checkbox('Indie').checked,false);
  assert.equal(checkbox('Pop'),undefined);
  const search=selector.querySelector('input[type="search"]');change(search,'metal');
  assert.equal(checkbox('Emo'),undefined);assert.ok(selector.querySelector('[aria-label="Genre Emo entfernen"]'));
  act(()=>checkbox('Metalcore').click());
  assert.match(selector.querySelector('[aria-label="Ausgewählte Standardgenres"]').textContent,/Emo.*Metalcore/);
  await settle(()=>button('Ins Portfolio übernehmen',dialog()).click());
  const request=calls.find(call=>call.url.endsWith('/portfolio')&&call.method==='POST');
  assert.deepEqual(JSON.parse(request.body),{name:'New Alternative Night',defaultStartTime:'23:00',standardGenres:['Emo','Metalcore'],seriesAlias:null});
  assert.ok(document.querySelector('[role="dialog"]'));assert.equal(control('Partyreihe zuordnen').value,'approved');assert.ok(!calls.some(call=>/instagram|publish|cancellations/.test(call.url)));
  assert.equal(reloadedEntries, 0); assert.equal(reloadedParties, 1); assert.equal(reloadedDrafts, 0);
  assert.equal(button('Bestätigen & übernehmen',dialog()).disabled,false);
  await settle(()=>button('Bestätigen & übernehmen',dialog()).click());
  assert.equal(JSON.parse(calls.find(call=>call.url.endsWith('/confirm')&&call.method==='POST').body).partyId,'approved');
  assert.ok(dialog().querySelector('a[href*="entry=imported-entry"]'));
});
test('Sonic Boom preselects all four source genres after the catalog loads and saves manual choices exactly', async () => {
  const sourceNames = ['Garage Rock', 'Rock’n’Roll', 'Punkrock', 'Soul'];
  await mount(candidate({title:'The Sonic Boom mit JAY TRIPLECHAIR & FRIENDS',
    assessment:assessment({recognition:{status:'NONE',parties:[]}}),
    genreCoverage:{matched:4,total:4,percent:100,names:sourceNames,sourceNames,eligible:true}}));
  const previousFetch = globalThis.fetch;
  let finishGenres;
  const pendingGenres = new Promise(resolve => { finishGenres = resolve; });
  globalThis.fetch = (url, options) => url === '/api/v1/genres' ? pendingGenres.then(() => new Response(JSON.stringify(genreRows))) : previousFetch(url, options);
  genreRows = [...sourceNames, 'Indie'].map(displayName => ({id:displayName,displayName,active:true}));
  await openReview();
  assert.match(dialog().textContent, /Genres werden geladen/);
  assert.equal(dialog().querySelectorAll('input[type="checkbox"]:checked').length, 0);
  await settle(() => finishGenres());
  const checked = () => [...dialog().querySelectorAll('fieldset input[type="checkbox"]:checked')].map(input => input.parentElement.textContent);
  assert.deepEqual(checked(), ['Garage Rock','Punkrock','Rock’n’Roll','Soul']);
  assert.match(dialog().textContent, /4 erkannte Genres aus dem aktiven Katalog vorausgewählt\./);
  const checkbox = name => [...dialog().querySelectorAll('label')].find(label => label.textContent === name).querySelector('input');
  act(() => checkbox('Soul').click());
  act(() => checkbox('Indie').click());
  candidates = candidates.map(item => ({...item, genreCoverage:{...item.genreCoverage,sourceNames:['Soul']}}));
  await settle(() => poll());
  assert.deepEqual(checked(), ['Garage Rock','Indie','Punkrock','Rock’n’Roll']);
  change(control('Standard-Startuhrzeit'), '23:00');
  await settle(() => button('Ins Portfolio übernehmen', dialog()).click());
  const payload = JSON.parse(calls.find(call => call.url.endsWith('/portfolio') && call.method === 'POST').body);
  assert.deepEqual(payload.standardGenres, ['Garage Rock','Punkrock','Rock’n’Roll','Indie']);
  assert.ok(document.querySelector('[role="dialog"]'));
  assert.equal(control('Partyreihe zuordnen').value, 'approved');
});

for (const [title, suggestedSeriesName] of [
  ['The Sonic Boom mit JAY TRIPLECHAIR & FRIENDS','The Sonic Boom'],
  ['BallroomBlitz! mit DER GRAUE KLAUS','BallroomBlitz!'],
  ['BallroomBlitz! mit ROMAN EMPIRE','BallroomBlitz!'],
  ['Shake Appeal mit BEATMIKE, FACE B & HOLLY WOOD','Shake Appeal'],
  ['Tanzen mit Freunden','Tanzen mit Freunden'],
]) test(`portfolio stem proposal stays editable and preserves original title: ${title}`, async () => {
  await mount(candidate({title,suggestedSeriesName,assessment:assessment({recognition:{status:'NONE',parties:[]}})}));
  await openReview();
  assert.equal(control('Name im Portfolio').value, suggestedSeriesName);
  assert.ok(dialog().textContent.includes(title));
  change(control('Name im Portfolio'), 'Manuell bestätigte Reihe');
  candidates = candidates.map(item => ({...item,suggestedSeriesName:'Späterer Vorschlag'}));
  await settle(() => poll());
  assert.equal(control('Name im Portfolio').value, 'Manuell bestätigte Reihe');
  change(control('Standard-Startuhrzeit'), '22:00');
  await settle(() => button('Ins Portfolio übernehmen', dialog()).click());
  assert.equal(JSON.parse(calls.find(call => call.url.endsWith('/portfolio') && call.method === 'POST').body).name, 'Manuell bestätigte Reihe');
});

test('genre defaults use current candidate source names and confirmed suggestions but preserve an empty manual selection', async () => {
  await mount(candidate({assessment:assessment({recognition:{status:'NONE',parties:[]}}),
    genreCoverage:{matched:1,total:1,percent:100,names:['Emo'],sourceNames:['Emo'],eligible:true}}));
  await openReview();
  const checked = () => [...dialog().querySelectorAll('fieldset input[type="checkbox"]:checked')].map(input => input.parentElement.textContent);
  assert.deepEqual(checked(), ['Emo']);
  candidates = candidates.map(item => ({...item,genreCoverage:{...item.genreCoverage,
    sourceNames:['Indie','Unknown','Pop'],suggestedStandardGenres:['Metalcore','Indie','Unknown','Pop']}}));
  await settle(() => poll());
  assert.deepEqual(checked(), ['Indie','Metalcore']);
  for (const input of dialog().querySelectorAll('fieldset input[type="checkbox"]:checked')) act(() => input.click());
  candidates = candidates.map(item => ({...item,genreCoverage:{...item.genreCoverage,sourceNames:['Emo']}}));
  await settle(() => poll());
  assert.deepEqual(checked(), []);
  change(control('Standard-Startuhrzeit'), '22:00');
  await settle(() => button('Ins Portfolio übernehmen', dialog()).click());
  assert.deepEqual(JSON.parse(calls.find(call => call.url.endsWith('/portfolio') && call.method === 'POST').body).standardGenres, []);
});

test('genre defaults exclude ambiguous active catalog names and inactive or unknown suggestions', async () => {
  await mount(candidate({assessment:assessment({recognition:{status:'NONE',parties:[]}}),
    genreCoverage:{matched:1,total:1,percent:100,names:['Emo'],sourceNames:['Emo','Pop','Unknown'],suggestedStandardGenres:['Pop','Unknown'],eligible:true}}));
  genreRows.push({id:'second-emo',displayName:'Emo',active:true});
  await openReview();
  assert.equal(dialog().querySelectorAll('fieldset input[type="checkbox"]:checked').length, 0);
  assert.match(dialog().textContent, /Keine erkannten Genres aus dem aktiven Katalog vorhanden/);
});

test('manual portfolio creation stays available below fifty percent without auto-selecting inferred genres',async()=>{
  await mount(candidate({title:'Unknown Emo Party',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{matched:1,total:4,percent:25,names:['Emo'],eligible:false}}));
  await openReview();assert.match(dialog().textContent,/1 von 4/);
  assert.ok(button('Ins Portfolio übernehmen',dialog()));
  const selector=[...dialog().querySelectorAll('fieldset')].find(item=>item.querySelector('legend')?.textContent==='Standardgenres');
  assert.equal(selector.querySelectorAll('input[type="checkbox"]:checked').length,0);
  change(control('Standard-Startuhrzeit'),'22:00');
  await settle(()=>button('Ins Portfolio übernehmen',dialog()).click());
  assert.deepEqual(JSON.parse(calls.find(call=>call.url.endsWith('/portfolio')&&call.method==='POST').body).standardGenres,[]);
  assert.ok(dialog());assert.equal(control('Partyreihe zuordnen').value,'approved');
});
test('Discovery hides location sources and excludes them from source notices and counts',async()=>{
  await mount(candidate());
  sourceRows=[{id:'mtc-source',venue_id:'blue-shell',venue_name:'MTC Cologne',url:'https://www.mtc-cologne.de/about',source_type:'OFFICIAL',source_kind:'WEBSITE',approved:true,status:'BROWSER_PARTIAL',quality:'PARTIAL',event_count:0}];
  await settle(()=>poll());
  await settle(()=>document.querySelector('.discovery-tools > summary').click());
  assert.doesNotMatch(document.body.textContent,/Location-Quellen|MTC Cologne|Nur teilweise ausgewertet/);
  assert.equal([...document.querySelectorAll('button')].some(item=>item.textContent==='Quelle hinzufügen'),false);
  assert.match(document.querySelector('.discovery-tools > summary').textContent,/0 Sonderquellen/);
  assert.equal(document.querySelector('.discovery-source-notice'),null);
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
  const fresh = document.querySelector('section[aria-label="Relevant · Neue Partyreihen"]');
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

test('portfolio confirmation prevents duplicate submissions and preserves the review after refresh', async () => {
  const fresh = candidate({ title: 'Neue Reihe', assessment: assessment({recognition: {status: 'NONE', parties: []}}), genreCoverage: {matched: 2, total: 4, percent: 50, names: ['Emo', 'Indie'], eligible: true} });
  await mount(fresh);
  await openReview();
  assert.ok(!calls.some(call => call.method === 'POST'));
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/portfolio') && options.method === 'POST') {
      await new Promise(resolve => setTimeout(resolve, 5));
    }
    return previous(url, options);
  };
  change(control('Standard-Startuhrzeit'),'22:30');
  const confirm = button('Ins Portfolio übernehmen', dialog());
  await settle(() => { confirm.click(); confirm.click(); });
  assert.equal(calls.filter(call => call.url.endsWith('/portfolio') && call.method === 'POST').length, 1);
  assert.ok(dialog());assert.equal(control('Partyreihe zuordnen').value,'approved');
  assert.equal(reloadedEntries, 0); assert.equal(reloadedDrafts, 0);
});

test('failed initial requests do not claim that Discovery is empty or up to date', async () => {
  await mount([], true);
  assert.match(document.querySelector('[role="alert"]').textContent, /Quelle nicht erreichbar/);
  assert.match(document.body.textContent, /Ergebnisse derzeit nicht verfügbar/);
  assert.doesNotMatch(document.body.textContent, /Keine neuen passenden Partyreihen|Keine offenen Ausnahmefälle/);
});


test('Prüfbedarf starts collapsed, shows its live count and preserves review controls across toggles', async () => {
  await mount([candidate({id:'review-a',title:'Alpha'}),candidate({id:'review-b',title:'Beta'})]);
  const section=document.querySelector('section[aria-label="Prüfbedarf"]');
  const accordion=section.querySelector('details.discovery-review');
  const summary=accordion.querySelector('summary');
  assert.ok(accordion);
  assert.equal(accordion.open,false);
  assert.equal(summary.getAttribute('aria-expanded'),'false');
  assert.match(summary.textContent,/Prüfbedarf 2/);
  assert.equal(document.querySelector('section[aria-label="Relevant · Neue Partyreihen"] details'),null);

  await settle(()=>summary.click());
  assert.equal(accordion.open,true);
  assert.equal(summary.getAttribute('aria-expanded'),'true');
  const search=section.querySelector('input[type="search"]');
  change(search,'Alpha');
  assert.deepEqual([...section.querySelectorAll('[data-record-id]')].map(row=>row.dataset.recordId),['review-a']);

  await settle(()=>summary.click());
  assert.equal(accordion.open,false);
  assert.equal(summary.getAttribute('aria-expanded'),'false');
  candidates=[...candidates,candidate({id:'review-c',title:'Gamma'})];
  await settle(()=>poll());
  assert.match(summary.textContent,/Prüfbedarf 3/);
  await settle(()=>summary.click());
  assert.equal(accordion.open,true);
  assert.equal(search.value,'Alpha');
  assert.deepEqual([...section.querySelectorAll('[data-record-id]')].map(row=>row.dataset.recordId),['review-a']);
  assert.ok(!calls.some(call=>['PUT','POST'].includes(call.method)));
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

test('rejection marking is local, deduplicated, survives polling and can be undone', async () => {
  await mount(candidate({inquiry_status:'SENT',dm_text:'Geprüfter Text'}));
  const posts=()=>calls.filter(call=>call.method==='POST').length;
  const before=posts();const mark=button('Ablehnung vormerken');await settle(()=>{mark.click();mark.click();});
  assert.equal(posts(),before);assert.ok(!document.querySelector('[role="dialog"]'));assert.equal(document.querySelectorAll('[data-record-id]').length,0);
  assert.match(document.body.textContent,/1 vorgemerkt/);assert.equal(candidates[0].status,'NEEDS_REVIEW');assert.equal(candidates[0].dm_text,'Geprüfter Text');
  await settle(()=>poll());assert.equal(document.querySelectorAll('[data-record-id]').length,0);
  await settle(()=>button(`Vormerkung zurücknehmen: ${candidates[0].title}`).click());assert.ok(document.querySelector('[data-record-id]'));assert.match(document.body.textContent,/0 vorgemerkt/);
});

test('failed batch keeps marks and error in confirmation and cancelling sends no request', async () => {
  await mount(candidate());await settle(()=>button('Ablehnung vormerken').click());
  const before=calls.filter(call=>call.method==='POST').length;
  await settle(()=>button('Ablehnen').click());await settle(()=>button('Abbrechen',dialog()).click());assert.equal(calls.filter(call=>call.method==='POST').length,before);
  await settle(()=>button('Ablehnen').click());
  const fallback=globalThis.fetch;globalThis.fetch=async(url,options={})=>url.endsWith('/reject-batch') ? new Response(JSON.stringify({message:'Bereits übernommen'}),{status:409}) : fallback(url,options);
  await settle(()=>button('1 Veranstaltungen ablehnen',dialog()).click());assert.match(dialog().textContent,/Bereits übernommen/);assert.match(document.body.textContent,/1 vorgemerkt/);
  assert.equal(candidates[0].status,'NEEDS_REVIEW');await settle(()=>button('Abbrechen',dialog()).click());
  await settle(()=>button(`Vormerkung zurücknehmen: ${candidates[0].title}`).click());assert.ok(document.querySelector('[data-record-id]'));
});

test('one hundred marks span both lists and one batch handles partial results without catalogue reloads',async()=>{
  await mount(candidate());
  candidates=Array.from({length:100},(_,index)=>candidate({id:`mark-${index}`,title:`Markierte Party ${index}`,updated_at:'2026-11-07T12:00:00Z',...(index%2 ? {assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{eligible:true,percent:75,names:['Emo']}} : {})}));
  await settle(()=>poll());
  const before=calls.length;
  for(let index=0;index<100;index++)await settle(()=>button('Ablehnung vormerken').click());
  assert.equal(calls.length,before);assert.match(document.body.textContent,/100 vorgemerkt/);assert.equal(document.querySelectorAll('[data-record-id]').length,0);
  await settle(()=>poll());assert.equal(document.querySelectorAll('[data-record-id]').length,0);
  const fallback=globalThis.fetch;
  globalThis.fetch=async(url,options={})=>{
    if(url.endsWith('/reject-batch')){
      calls.push({url,...options});const ids=JSON.parse(options.body).candidateIds;
      assert.equal(new Set(ids).size,100);
      const rejectedIds=ids.slice(0,99);candidates=candidates.map(item=>rejectedIds.includes(item.id)?{...item,status:'REJECTED',decision_origin:'MANUAL'}:item);
      return new Response(JSON.stringify({requested:100,rejected:99,alreadyRejected:0,rejectedIds,alreadyRejectedIds:[],conflicts:[{id:ids[99],reason:'Inzwischen geändert'}]}));
    }
    return fallback(url,options);
  };
  await settle(()=>button('Ablehnen').click());assert.match(dialog().textContent,/100 Veranstaltungen/);
  await settle(()=>button('100 Veranstaltungen ablehnen',dialog()).click());
  assert.equal(calls.filter(call=>call.url.endsWith('/reject-batch')).length,1);assert.equal(calls.some(call=>call.url.endsWith('/reject')),false);
  assert.match(document.body.textContent,/99 Veranstaltungen abgelehnt/);assert.match(document.body.textContent,/1 Konflikte/);assert.match(document.body.textContent,/1 vorgemerkt/);
  assert.equal(reloadedEntries+reloadedParties+reloadedDrafts,0);
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
  assert.deepEqual(JSON.parse(added.body),{name:'QRUSH',url:'https://qrushapp.de/',approved:false,itemSelector:'',titleSelector:'',dateSelector:''});
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


test('special-source edit preloads configuration, retains focus and updates the existing identity',async()=>{
  await mount(candidate());
  sourceRows=[{id:'qrush',name:'QRUSH',special_source:true,venue_id:null,venue_name:'',url:'https://qrushapp.de/',source_type:'EXTERNAL',approved:false,status:'EVENTS_FOUND',item_selector:'.event',title_selector:'h2',date_selector:'time'}];
  await settle(()=>poll());await settle(()=>document.querySelector('.discovery-tools > summary').click());
  await settle(()=>button('Sonderquelle editieren').click());
  assert.equal(control('Name der Sonderquelle').value,'QRUSH');
  assert.equal(control('Event-Container').value,'.event');
  const approval=control('Quelle ausdrücklich zur automatischen Übernahme freigeben');
  assert.equal(approval.checked,false);
  await settle(()=>approval.click());
  change(control('Name der Sonderquelle'),'QRUSH Köln');
  const url=control('Quell-URL');change(url,'https://qrushapp.de/eventfinder/koeln');assert.equal(document.activeElement,url);
  await settle(()=>dialog().querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  const edited=calls.find(call=>call.method==='PUT');
  assert.equal(edited.url,'/api/v1/discovery/special-sources/qrush');
  assert.deepEqual(JSON.parse(edited.body),{name:'QRUSH Köln',url:'https://qrushapp.de/eventfinder/koeln',approved:true,itemSelector:'.event',titleSelector:'h2',dateSelector:'time'});
  assert.equal(calls.some(call=>call.method==='POST'||call.method==='DELETE'),false);
  assert.equal(document.querySelector('[role="dialog"]'),null);
});

test('special-source edit conflict retains edited values and leaves the existing source visible',async()=>{
  await mount(candidate());
  sourceRows=[{id:'qrush',name:'QRUSH',special_source:true,venue_id:null,venue_name:'',url:'https://qrushapp.de/',source_type:'EXTERNAL',approved:false,status:'NOT_CHECKED',item_selector:'',title_selector:'',date_selector:''}];
  await settle(()=>poll());await settle(()=>document.querySelector('.discovery-tools > summary').click());
  await settle(()=>button('Sonderquelle editieren').click());
  change(control('Name der Sonderquelle'),'Geänderter Name');
  globalThis.fetch=async()=>new Response(JSON.stringify({message:'Diese Sonderquelle ist bereits vorhanden'}),{status:409});
  await settle(()=>dialog().querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(control('Name der Sonderquelle').value,'Geänderter Name');assert.match(dialog().textContent,/bereits vorhanden/);
  assert.equal(sourceRows[0].name,'QRUSH');
});


test('geography accordion starts collapsed and retains its contents and live count when toggled',async()=>{
  await mount([]);
  const geography=document.querySelector('section[aria-label="Standortklärung"]');
  const accordion=geography.querySelector('details');
  const header=accordion.querySelector('summary');
  assert.equal(accordion.open,false);
  assert.equal(header.getAttribute('aria-expanded'),'false');
  assert.match(header.textContent,/Standortklärung 0/);
  geoRows=[{id:'unknown',title:'Ungeklärte Party',status:'UNRESOLVED',source_url:'https://portal.example/event',location:'Club',reason:'Kein Ortsbeleg'},
    {id:'bonn',title:'Auswärtige Party',status:'OUTSIDE',source_url:'https://portal.example/bonn',location:'Gleis8',reason:'Ort Bonn'}];
  await settle(()=>poll());
  assert.equal(accordion.open,false);
  assert.match(header.textContent,/Standortklärung 1/);
  header.focus();
  assert.ok(document.activeElement===header);
  await settle(()=>header.click());
  assert.equal(accordion.open,true);
  assert.equal(header.getAttribute('aria-expanded'),'true');
  const clarify=button('Standort klären',geography);
  assert.match(geography.textContent,/Ungeklärte Party/);
  assert.match(geography.textContent,/Außerhalb Kölns \(1\)/);
  assert.ok(button('Gespeicherte Standorte neu bewerten',geography));
  await settle(()=>header.click());
  assert.equal(accordion.open,false);
  assert.equal(header.getAttribute('aria-expanded'),'false');
  await settle(()=>header.click());
  assert.equal(accordion.open,true);
  assert.equal(header.getAttribute('aria-expanded'),'true');
  assert.ok(button('Standort klären',geography)===clarify);
});

test('unknown geography stays separate from party review until an explicit address confirmation',async()=>{
  await mount(candidate({special_source:true,geo_status:'UNRESOLVED',venue_id:null}));
  geoRows=[{id:'geo-one',title:'Ungeklärte Indie Party',source_url:'https://portal.example/event',status:'UNRESOLVED',location:'Blue Shell',reason:'Kein Ortsbeleg',place:{city:'',street:''}}];
  await settle(()=>poll());
  assert.equal(document.querySelector('section[aria-label="Prüfbedarf"] [data-record-id]'),null);
  assert.match(document.querySelector('section[aria-label="Standortklärung"]').textContent,/Ungeklärte Indie Party/);
  const geography=document.querySelector('section[aria-label="Standortklärung"]');
  const header=geography.querySelector('summary');
  assert.match(header.textContent,/Standortklärung 1/);
  await settle(()=>header.click());
  await settle(()=>button('Standort klären').click());
  assert.equal(button('Kölner Standort bestätigen',dialog()).disabled,true);
  change(control('Location'),'blue-shell');change(control('Straße und Hausnummer'),'Luxemburger Straße 32');
  await settle(()=>dialog().querySelector('input[type="checkbox"]').click());
  const fallback=globalThis.fetch;
  globalThis.fetch=async(url,options={})=>{if(url.endsWith('/geography/geo-one/confirm')){calls.push({url,...options});geoRows=[];return new Response(null,{status:204});}return fallback(url,options);};
  await settle(()=>dialog().querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  const saved=calls.find(call=>call.url.endsWith('/geography/geo-one/confirm'));
  assert.deepEqual(JSON.parse(saved.body),{venueId:'blue-shell',venueName:null,city:'Köln',street:'Luxemburger Straße 32',proofUrl:'https://portal.example/event',confirmed:true});
  assert.equal(document.querySelector('[role="dialog"]'),null);
  assert.match(header.textContent,/Standortklärung 0/);
  assert.equal(header.getAttribute('aria-expanded'),'true');
  assert.match(geography.textContent,/Keine ungeklärten Standortfunde/);
  assert.equal(calls.some(call=>call.url.includes('/candidates/')&&call.method==='POST'),false);
});

test('outside geography is excluded from normal review and never offers a Cologne confirmation',async()=>{
  await mount([]);
  geoRows=[{id:'bonn',title:'Köln Party',source_url:'https://portal.example/koeln',status:'OUTSIDE',location:'Gleis8',reason:'Ort Bonn',place:{city:'Bonn',street:'Straße 1'}}];
  await settle(()=>poll());
  const geography=document.querySelector('[aria-label="Standortklärung"]');
  await settle(()=>geography.querySelector('summary').click());
  assert.match(geography.querySelector('summary').textContent,/Standortklärung 0/);
  assert.match(geography.textContent,/Außerhalb Kölns \(1\)/);assert.match(geography.textContent,/Gleis8 · Bonn/);
  assert.equal([...geography.querySelectorAll('button')].some(item=>item.textContent==='Standort klären'),false);
  assert.equal(document.querySelectorAll('[data-record-id]').length,0);
});

test('geography confirmation failures preserve the new location and proof inputs',async()=>{
  await mount([]);geoRows=[{id:'unknown',title:'Party',source_url:'https://portal.example/event',status:'UNRESOLVED',location:'Neuer Club',reason:'Unbekannt'}];
  await settle(()=>poll());await settle(()=>document.querySelector('section[aria-label="Standortklärung"] summary').click());await settle(()=>button('Standort klären').click());
  change(control('Location'),'new');change(control('Name der neuen Location'),'Neuer Club Köln');change(control('Straße und Hausnummer'),'Straße 12');
  await settle(()=>dialog().querySelector('input[type="checkbox"]').click());
  globalThis.fetch=async()=>new Response(JSON.stringify({message:'Ortsbelege widersprechen sich'}),{status:409});
  await settle(()=>dialog().querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(control('Name der neuen Location').value,'Neuer Club Köln');assert.equal(control('Straße und Hausnummer').value,'Straße 12');
  assert.match(dialog().textContent,/Ortsbelege widersprechen sich/);
});


test('successful confirmation exposes the exact calendar target and prevents duplicate submission', async()=>{
 await mount(candidate({party_id:'paradise'}));
 const fallback=globalThis.fetch;
 globalThis.fetch=async(url,options={})=>url.endsWith('/confirm') ? new Response(JSON.stringify({calendarEntryId:'new/import'}),{status:200}) : fallback(url,options);
 await openReview();await settle(()=>button('Bestätigen & übernehmen',dialog()).click());
 const link=dialog().querySelector('a[href^="/dashboard/kalender?entry="]');
 assert.equal(link.textContent,'Im Kalender anzeigen');assert.equal(link.getAttribute('href'),'/dashboard/kalender?entry=new%2Fimport');
 assert.equal(button('Bestätigen & übernehmen',dialog()).disabled,true);
 assert.ok(reloadedEntries>0);
});


test('persisted Discovery progress updates by polling and remains visible after a page reload', async () => {
  await mount([]);
  const running = { id: 'persisted-scan', kind: 'SCAN', status: 'RUNNING', report: '', summary: '{}', totalUnits: 3, completedUnits: 1, failedUnits: 1, progressPercent: 33, currentLabel: 'Quelle B', phase: 'SPECIAL_SOURCES', updatedAt: '2026-11-06T21:00:00Z' };
  operationRows = [running];
  await settle(() => poll());
  let bar = document.querySelector('[role="progressbar"]');
  assert.ok(bar);
  assert.equal(bar.getAttribute('aria-valuenow'), '33');
  assert.equal(bar.getAttribute('aria-valuemin'), '0');
  assert.equal(bar.getAttribute('aria-valuemax'), '100');
  assert.match(document.body.textContent, /1 von 3 Quellen\/Locations verarbeitet/);
  assert.match(document.body.textContent, /Aktuelle Quelle: Quelle B/);
  assert.equal(document.querySelector('time').dateTime, running.updatedAt);
  operationRows = [{ ...running, completedUnits: 2, progressPercent: 67, currentLabel: 'Location C', phase: 'LOCATIONS' }];
  await settle(() => poll());
  assert.equal(document.querySelector('[role="progressbar"]').getAttribute('aria-valuenow'), '67');
  assert.match(document.body.textContent, /Aktuelle Location: Location C/);
  act(() => root.unmount());
  document.body.replaceChildren();
  const container = document.createElement('div'); document.body.append(container);
  root = createRoot(container);
  act(() => root.render(h(StrictMode, null, h(Page))));
  await settle();
  bar = document.querySelector('[role="progressbar"]');
  assert.equal(bar.getAttribute('aria-valuenow'), '67', 'Reload reads the persisted operation instead of restarting at zero');
  operationRows = [{ ...operationRows[0], status: 'PARTIAL', completedUnits: 3, progressPercent: 100, phase: 'PARTIAL', currentLabel: null }];
  await settle(() => poll());
  assert.equal(document.querySelector('[role="progressbar"]').getAttribute('aria-valuenow'), '100');
  assert.match(document.body.textContent, /1 Arbeitseinheit mit Fehler. Alle vorgesehenen Einheiten wurden verarbeitet/);
});

test('failed and interrupted scans keep real progress; empty and reset states stay distinct', async () => {
  await mount([]);
  for (const status of ['FAILED', 'INTERRUPTED']) {
    operationRows = [{ id: 'stopped', kind: 'SCAN', status, totalUnits: 3, completedUnits: 1, failedUnits: 0, progressPercent: 33 }];
    await settle(() => poll());
    assert.equal(document.querySelector('[role="progressbar"]').getAttribute('aria-valuenow'), '33');
    assert.match(document.body.textContent, /zuletzt gespeicherte Fortschritt/);
  }
  operationRows = [{ id: 'empty', kind: 'SCAN', status: 'COMPLETED', totalUnits: 0, completedUnits: 0, progressPercent: 100 }];
  await settle(() => poll());
  assert.match(document.body.textContent, /Keine Quellen oder Locations für diesen Lauf vorgesehen/);
  operationRows = [{ id: 'reset', kind: 'ALL', status: 'RUNNING', progressPercent: null }];
  await settle(() => poll());
  assert.equal(document.querySelector('[role="progressbar"]'), null);
  assert.match(document.body.textContent, /Neubewertung läuft/);
  assert.doesNotMatch(document.body.textContent, /Suchlauf läuft/);
  operationRows = [{ id: 'legacy', kind: 'SCAN', status: 'COMPLETED' }];
  await settle(() => poll());
  assert.equal(document.querySelector('[role="progressbar"]'), null);
});


test('manual series creation without a date or confirmed venue never imports a calendar entry', async () => {
  await mount(candidate({title:'Unbestätigte Reihe',venue_id:null,special_source:true,geo_status:'INSIDE',event_date:null,assessment:assessment({recognition:{status:'NONE',parties:[]},verification:{status:'MISSING_DATA',reasons:[],date:null},importBlockers:['Datum oder Location fehlt']}),genreCoverage:{matched:0,total:0,percent:0,names:[],eligible:false}}));
  await openReview();
  change(control('Standard-Startuhrzeit'),'22:00');
  await settle(()=>button('Ins Portfolio übernehmen',dialog()).click());
  assert.equal(control('Partyreihe zuordnen').value,'approved');
  assert.equal(button('Bestätigen & übernehmen',dialog()).disabled,true);
  assert.equal(control('Korrigiertes / bestätigtes Datum').value,'');
  assert.equal(control('Veranstaltungsort zuordnen').value,'');
  assert.ok(!calls.some(call=>call.url.endsWith('/confirm')));
});

test('genre-catalog errors and failed creation preserve the inputs and selected genre chips', async () => {
  await mount(candidate({title:'Neue Reihe',assessment:assessment({recognition:{status:'NONE',parties:[]}})}));
  const previous=globalThis.fetch;
  globalThis.fetch=async (url,options={})=>url==='/api/v1/genres'?new Response('{}',{status:503}):previous(url,options);
  await openReview();
  assert.match(dialog().textContent,/Genreverwaltung antwortete mit HTTP 503/);
  change(control('Standard-Startuhrzeit'),'22:00');
  assert.equal(button('Ins Portfolio übernehmen',dialog()).disabled,true);
  await settle(()=>dialog().querySelector('[aria-label="Dialog schließen"]').click());
  globalThis.fetch=previous;
  await openReview();
  change(control('Standard-Startuhrzeit'),'22:30');
  const emo=[...dialog().querySelectorAll('label')].find(label=>label.textContent==='Emo').querySelector('input');
  act(()=>emo.click());
  globalThis.fetch=async(url,options={})=>url.endsWith('/portfolio')?new Response(JSON.stringify({message:'Standardgenre inzwischen inaktiv'}),{status:400}):previous(url,options);
  await settle(()=>button('Ins Portfolio übernehmen',dialog()).click());
  assert.match(dialog().textContent,/Standardgenre inzwischen inaktiv/);
  assert.equal(control('Standard-Startuhrzeit').value,'22:30');
  assert.ok(dialog().querySelector('[aria-label="Genre Emo entfernen"]'));
  act(()=>dialog().querySelector('[aria-label="Genre Emo entfernen"]').click());
  assert.equal(emo.checked,false);
});

test('comma-separated keyword rules are created together and individually editable',async()=>{
 await mount(candidate());await settle(()=>button('Quellen & Konfiguration').click());
 const section=document.querySelector('[aria-label="Schlagwort-Ausschlüsse"]');assert.ok(section);assert.equal(keywordRows.length,0);
 const field=label=>[...section.querySelectorAll('label')].find(item=>item.textContent.trim().startsWith(label)).querySelector('input, textarea');
 await settle(()=>change(field('Schlagwörter (durch Komma getrennt)'),'Karneval, B2B, Ausverkauft, Jecke, b2b'));
 await settle(()=>section.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
 assert.deepEqual(keywordRows.map(row=>row.keyword),['Karneval','B2B','Ausverkauft','Jecke']); 
 assert.ok(keywordRows.every(row=>row.enabled && row.reason.startsWith('Automatischer Ausschluss: ')));
 assert.ok(calls.some(call=>call.method==='POST' && call.url.endsWith('/keyword-rules/batch')));
 await settle(()=>button('Bearbeiten: B2B',section).click());
 await settle(()=>change(field('Ausschlussgrund'),'B2B gesperrt'));
 await settle(()=>field('Aktiv').click());
 await settle(()=>section.querySelectorAll('form')[0].dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
 assert.equal(keywordRows.find(row=>row.keyword==='B2B').enabled,false);
 assert.ok(calls.some(call=>call.method==='PUT' && call.url.endsWith('/keyword-rules/keyword-test-1')));
});

test('keyword warnings on a confirmed venue stay visible in review rather than new-series promotion',async()=>{
 await mount(candidate({keywordWarning:'Schlagwortfilter „B2B“: Ausdrücklich prüfen',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{eligible:true,percent:100,names:['Emo']}}));
 assert.match(document.querySelector('[aria-label="Prüfbedarf"]').textContent,/Schlagwortfilter.*B2B.*Ausdrücklich prüfen/);
 assert.equal(document.querySelector('[aria-label="Relevant · Neue Partyreihen"] [data-record-id]'),null);
});

test('Discovery portfolio recurrence requires confirmed location and start date and only creates planned entries', async () => {
  await mount(candidate({title:'BallroomBlitz! mit DER GRAUE KLAUS',suggestedSeriesName:'BallroomBlitz!',assessment:assessment({recognition:{status:'NONE',parties:[]}})}));
  await openReview();change(control('Standard-Startuhrzeit'),'23:00');
  change(control('Wiederholung'),'monthly_weekday');
  assert.equal(button('Ins Portfolio übernehmen',dialog()).disabled,true);
  change(control('Wochentag'),'5');change(control('Woche im Monat'),'1');
  change(control('Bestätigte Turnus-Location'),'blue-shell');
  assert.equal(button('Ins Portfolio übernehmen',dialog()).disabled,true);
  change(control('Planung ab Datum'),'2026-11-07');
  assert.equal(button('Ins Portfolio übernehmen',dialog()).disabled,false);
  assert.match(dialog().textContent,/Geplant – nicht bestätigt/);
  await settle(()=>button('Ins Portfolio übernehmen',dialog()).click());
  const payload=JSON.parse(calls.find(call=>call.method==='POST'&&call.url.endsWith('/portfolio')).body);
  assert.equal(payload.name,'BallroomBlitz!');
  assert.deepEqual(payload.recurrence,{kind:'monthly_weekday',weekday:5,occurrence:1,locationId:'blue-shell',startDate:'2026-11-07'});
  assert.equal(control('Partyreihe zuordnen').value,'approved');
  assert.equal(reloadedEntries,1);assert.ok(!calls.some(call=>call.url.endsWith('/confirm')||/publish|instagram/.test(call.url)));
});

 test('Unclassifiable events are separate from relevant editorial decisions and stay inspectable', async () => {
  await mount([candidate({id:'unclear',title:'Unbekannte Clubnacht',matching_class:'UNCLASSIFIABLE',assessment:assessment({recognition:{status:'NONE',parties:[]}}),genreCoverage:{total:0,percent:0,eligible:false,names:[],basis:'MISSING'}}),candidate({id:'known',title:'Misery',party_id:'misery'})]);
  const unclear=document.querySelector('[aria-label="Unklare Angaben"]');
  assert.match(unclear.textContent,/Nicht ausreichend klassifizierbar/);
  assert.ok(unclear.querySelector('[data-record-id="unclear"]'));
  assert.equal(document.querySelector('[aria-label="Prüfbedarf"] [data-record-id="unclear"]'),null);
  assert.ok(document.querySelector('[aria-label="Prüfbedarf"] [data-record-id="known"]'));
  assert.ok(calls.some(call=>call.url.endsWith('?filter=UNCLEAR')));
  assert.ok(calls.some(call=>call.url.endsWith('?filter=RELEVANT')));
 });

// A confirmed calendar mutation and the following read-only refreshes are separate outcomes.
test('confirmed import ID is visible before pending refreshes finish and pending dismissal never discards it', async () => {
  await mount(candidate({party_id:'paradise'}));
  const original = catalogue.reloadEntries;
  let finish;
  const gate = new Promise(resolve => { finish = resolve; });
  let requestedOptions;
  catalogue.reloadEntries = async options => { requestedOptions = options; await original(); await gate; };
  let prompts = 0;
  window.confirm = () => { prompts++; return false; };
  try {
    await openReview();
    await settle(() => { const confirm = button('Bestätigen & übernehmen', dialog()); confirm.click(); confirm.click(); });
    assert.deepEqual(requestedOptions, {throwOnError:true});
    const saved = dialog().querySelector('[aria-label="Gespeicherter Kalendereintrag"]');
    assert.ok(saved, 'Confirmed ID must be retained before awaiting a catalogue refresh');
    assert.match(saved.querySelector('[role="status"]').textContent, /Termin ist im Kalender gespeichert.*Ansichten werden aktualisiert/);
    assert.equal(saved.querySelector('a').getAttribute('href'), '/dashboard/kalender?entry=imported-entry');
    assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
    assert.equal(dialog().querySelector('[data-modal-close]').disabled, true);
    await settle(() => document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true,cancelable:true})));
    assert.equal(prompts, 0);
    assert.ok(dialog().querySelector('[aria-label="Gespeicherter Kalendereintrag"]'));
    assert.equal(calls.filter(call => call.url.endsWith('/confirm') && call.method === 'POST').length, 1);
    await settle(() => finish());
    assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
    assert.equal(dialog().querySelector('[data-modal-close]').disabled, false);
  } finally { finish(); catalogue.reloadEntries = original; window.confirm = () => true; }
});

for (const [method, label] of [['reloadEntries','Kalender'], ['reloadDrafts','Redaktion'], ['reloadParties','Portfolio']]) {
  test(`confirmed import survives ${label} refresh failure and retries reads without a second confirmation`, async () => {
    await mount(candidate({party_id:'paradise'}));
    const original = catalogue[method];
    let attempts = 0;
    const requested = [];
    catalogue[method] = async options => {
      attempts++; requested.push(options); await original();
      if (attempts === 1) throw new Error(`Synthetic ${label} read outage`);
    };
    try {
      await openReview();
      await settle(() => button('Bestätigen & übernehmen', dialog()).click());
      const saved = dialog().querySelector('[aria-label="Gespeicherter Kalendereintrag"]');
      assert.equal(saved.querySelector('a').getAttribute('href'), '/dashboard/kalender?entry=imported-entry');
      assert.match(saved.querySelector('[role="status"]').textContent, /Termin ist im Kalender gespeichert/);
      assert.match(saved.querySelector('[role="alert"]').textContent, new RegExp(`Kalenderimport ist bestätigt.*Noch nicht aktualisiert: ${label}`));
      assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
      assert.equal(button('Speichern', dialog()).disabled, true);
      await settle(() => button('Bestätigen & übernehmen', dialog()).click());
      assert.equal(calls.filter(call => call.url.endsWith('/confirm') && call.method === 'POST').length, 1);
      await settle(() => button('Ansichten erneut laden', dialog()).click());
      assert.equal(attempts, 2);
      assert.deepEqual(requested, [{throwOnError:true},{throwOnError:true}]);
      assert.equal(reloadedEntries, 2); assert.equal(reloadedParties, 2); assert.equal(reloadedDrafts, 2);
      assert.equal(saved.querySelector('[role="alert"]'), null);
      assert.match(document.body.textContent, /Termin bestätigt und in den Kalender übernommen. Ansichten aktualisiert/);
      assert.equal(calls.filter(call => call.url.endsWith('/confirm') && call.method === 'POST').length, 1);
      assert.ok(!calls.some(call => call.method === 'PUT' || call.url.endsWith('/portfolio')));
    } finally { catalogue[method] = original; }
  });
}

test('confirmed import survives Discovery overview failure and retries the overview without re-importing', async () => {
  await mount(candidate({party_id:'paradise'}));
  const fallback = globalThis.fetch;
  let overviewFails = false;
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/confirm') && options.method === 'POST') {
      const result = await fallback(url, options); overviewFails = true; return result;
    }
    if (overviewFails && url.includes('/api/v1/discovery?filter=')) return new Response(JSON.stringify({message:'Discovery-Ansicht vorübergehend nicht erreichbar'}), {status:503});
    return fallback(url, options);
  };
  await openReview();
  await settle(() => button('Bestätigen & übernehmen', dialog()).click());
  assert.match(dialog().textContent, /Discovery-Ansicht vorübergehend nicht erreichbar/);
  assert.ok(dialog().querySelector('a[href="/dashboard/kalender?entry=imported-entry"]'));
  assert.equal(button('Bestätigen & übernehmen', dialog()).disabled, true);
  overviewFails = false;
  await settle(() => button('Ansichten erneut laden', dialog()).click());
  assert.equal(calls.filter(call => call.url.endsWith('/confirm') && call.method === 'POST').length, 1);
  assert.doesNotMatch(dialog().textContent, /Discovery-Ansicht vorübergehend nicht erreichbar/);
});
