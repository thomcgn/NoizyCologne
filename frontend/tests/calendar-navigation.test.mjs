import assert from 'node:assert/strict';
import {afterEach, test} from 'node:test';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {JSDOM} from 'jsdom';
import ts from 'typescript';
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost'});
for(const name of ['window','document','HTMLElement','Event','MouseEvent']) globalThis[name]=dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const require=createRequire(import.meta.url);
const React=require('react');const {act,createElement:h}=React;
const {createRoot}=require('react-dom/client');
let query='';let root;let loads=0;
const catalog={entries:[],entriesLoading:false,entriesError:null,parties:[{id:'party',name:'Importierte Rocknacht'}],venues:[{id:'venue',name:'Club'}],actors:[],reloadEntries:async()=>{loads++;},reloadParties:async()=>{}};
const modules=new Map();
function load(url){
 if(modules.has(url.href))return modules.get(url.href);
 const loaded={exports:{}};
 const code=ts.transpileModule(readFileSync(url,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
 const localRequire=specifier=>{
  if(specifier==='next/navigation')return {useSearchParams:()=>new URLSearchParams(query)};
  if(specifier.endsWith('/CatalogProvider'))return {useCatalog:()=>catalog,toDateKey:date=>`${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`};
  if(specifier.endsWith('/EntryEditor'))return {default:()=>null};
  if(specifier.endsWith('/workspaceActions'))return {useWorkspaceAction:()=>{}};
  if(specifier.endsWith('/DashboardModal'))return {default:props=>props.open?h('section',{role:'dialog','aria-label':props.title},props.children):null};
  if(specifier.endsWith('/DataCollection'))return {default:props=>h('div',null,props.items.map(item=>h('p',{key:item.id},props.getName(item))))};
  if(specifier.startsWith('.'))return load(new URL(specifier+'.ts',url));
  return require(specifier);
 };
 new Function('require','module','exports',code)(localRequire,loaded,loaded.exports);modules.set(url.href,loaded.exports);return loaded.exports;
}
const {berlinDate,calendarEntryHref}=load(new URL('../src/app/dashboard/kalender/calendarNavigation.ts',import.meta.url));
const Page=load(new URL('../src/app/dashboard/kalender/page.tsx',import.meta.url)).default;
async function settle(work=()=>{}){await act(async()=>{await work();await new Promise(resolve=>setTimeout(resolve,25));});}
async function mount(){document.body.innerHTML='<div id="root"></div>';root=createRoot(document.getElementById('root'));await settle(()=>root.render(h(Page)));await settle();}
afterEach(async()=>{if(root)await settle(()=>root.unmount());root=null;query='';loads=0;catalog.entries=[];});
test('Berlin civil month handles midnight, summer time and year boundaries',()=>{
 for(const [instant,expected] of [['2026-10-31T23:30:00Z',[2026,10,1]],['2026-12-31T23:30:00Z',[2027,0,1]],['2026-06-30T22:30:00Z',[2026,6,1]]]){
  const date=berlinDate(new Date(instant));assert.deepEqual([date.getFullYear(),date.getMonth(),date.getDate()],expected);
 }
 assert.equal(calendarEntryHref('entry/a & b'),'/dashboard/kalender?entry=entry%2Fa%20%26%20b');
});
test('calendar starts in the current Berlin month and reloads on re-entry and explicit refresh',async()=>{
 await mount();const today=berlinDate();const expected=today.toLocaleDateString('de-DE',{month:'long',year:'numeric'});
 assert.equal(document.querySelector('h2').textContent,expected);assert.equal(loads,1);
 await settle(()=>[...document.querySelectorAll('button')].find(button=>button.textContent==='Aktualisieren').click());assert.equal(loads,2);
 await settle(()=>root.unmount());root=null;await mount();assert.equal(loads,3);
});
test('a linked import arriving after refresh opens its actual day, month and details',async()=>{
 query='entry=imported';await mount();assert.match(document.body.textContent,/nicht mehr vorhanden/);
 catalog.entries=[{id:'imported',date:'2027-03-19',type:'event',partyId:'party',locationId:'venue',notes:'Automatischer Import',startTime:'23:00',entryPrice:'',mentionDjIds:[]}];
 await settle(()=>root.render(h(Page)));await settle();
 assert.match(document.querySelector('h2').textContent,/19. März 2027/);
 assert.equal(document.querySelector('[role="dialog"]').getAttribute('aria-label'),'Importierte Rocknacht');
 assert.match(document.querySelector('[role="dialog"]').textContent,/Automatischer Import/);
 assert.doesNotMatch(document.body.textContent,/nicht mehr vorhanden/);
});
