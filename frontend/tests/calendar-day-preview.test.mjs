import assert from 'node:assert/strict';
import {afterEach, test} from 'node:test';
import {createRequire} from 'node:module';
import {readFileSync,existsSync} from 'node:fs';
import {JSDOM} from 'jsdom';
import ts from 'typescript';
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost'});
for(const name of ['window','document','HTMLElement','Event','MouseEvent','KeyboardEvent','Element','HTMLDetailsElement']) globalThis[name]=dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
globalThis.getComputedStyle=dom.window.getComputedStyle.bind(dom.window);
let hoverSupported=true;
window.matchMedia=()=>({matches:hoverSupported});
const require=createRequire(import.meta.url);
const React=require('react');const {act,createElement:h}=React;
const {createRoot}=require('react-dom/client');
let query='';let root;let loads=0;
const catalog={entries:[],entriesLoading:false,entriesError:null,parties:[{id:'party',name:'Rocknacht',color:'#c73153'},{id:'early',name:'Frühe Party'},{id:'tie',name:'Zeitgleich'},{id:'late',name:'Späte Party'}],venues:[{id:'venue',name:'Club'},{id:'other',name:'Andere Location'}],actors:[],reloadEntries:async()=>{loads++;},reloadParties:async()=>{}};
const modules=new Map();
function load(url){
 if(modules.has(url.href))return modules.get(url.href);
 const loaded={exports:{}};
 const code=ts.transpileModule(readFileSync(url,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
 const localRequire=specifier=>{
  if(specifier==='next/image')return {default:({src,alt,width,height,className,style})=>h('img',{src,alt,width,height,className,style})};
  if(specifier==='next/navigation')return {useSearchParams:()=>new URLSearchParams(query)};
  if(specifier.endsWith('/CatalogProvider'))return {useCatalog:()=>catalog,toDateKey:date=>`${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`};
  if(specifier.endsWith('/EntryEditor'))return {default:()=>null};
  if(specifier.endsWith('/workspaceActions'))return {useWorkspaceAction:()=>{}};
  if(specifier.endsWith('/DataCollection'))return {default:props=>h('div',null,h('input',{type:'search',value:props.query,onChange:()=>{},onInput:event=>props.onQueryChange(event.currentTarget.value)}),props.toolbar,props.items.map(item=>h('p',{key:item.id},props.getName(item))))};
  if(specifier.startsWith('.')){const path=new URL(specifier+'.ts',url);return load(existsSync(path)?path:new URL(specifier+'.tsx',url));}
  return require(specifier);
 };
 new Function('require','module','exports',code)(localRequire,loaded,loaded.exports);modules.set(url.href,loaded.exports);return loaded.exports;
}
const {berlinDate}=load(new URL('../src/app/dashboard/kalender/calendarNavigation.ts',import.meta.url));
const Page=load(new URL('../src/app/dashboard/kalender/page.tsx',import.meta.url)).default;
async function settle(work=()=>{}){await act(async()=>{await work();await new Promise(resolve=>setTimeout(resolve,25));});}
async function mount(){document.body.innerHTML='<div id="root"></div>';root=createRoot(document.getElementById('root'));await settle(()=>root.render(h(Page)));await settle();}
afterEach(async()=>{if(root)await settle(()=>root.unmount());root=null;query='';loads=0;hoverSupported=true;timers.clear();clock=0;catalog.entries=[];catalog.parties[0].defaultFlyer=null;});

let clock=0;let timerId=0;const timers=new Map();
window.setTimeout=(work,delay=0)=>{const id=++timerId;timers.set(id,{work,at:clock+delay});return id;};
window.clearTimeout=id=>timers.delete(id);
function advance(ms){act(()=>{clock+=ms;for(const [id,timer] of [...timers])if(timer.at<=clock){timers.delete(id);timer.work();}});}
const date=(()=>{const d=berlinDate();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;})();
function entry(id,partyId,startTime,extra={}){return {id,date,type:'event',partyId,startTime,locationId:'venue',notes:'',mentionDjIds:[],eventStatus:'ACTIVE',eventFlyer:null,...extra};}
async function setup(){
 catalog.entries=[entry('unknown','party','',{locationId:'missing'}),entry('late','late','23:00',{eventStatus:'CANCELLED'}),entry('early','early','21:00'),entry('tie','tie','21:00'),{id:'closure',date,type:'closure',reason:'Geschlossene Gesellschaft',locationId:'venue',notes:''}];
 await mount();
 const cell=document.querySelector(`[data-calendar-day="${date}"]`);
 cell.getBoundingClientRect=()=>({left:0,right:100,top:0,bottom:100,width:100,height:100});
 return cell;
}
function pointer(target,type,extra={}){
 const event=new Event(type,{bubbles:true,cancelable:true});
 Object.assign(event,{pointerType:'mouse',pointerId:1,isPrimary:true,button:0,clientX:20,clientY:20,...extra});
 act(()=>target.dispatchEvent(event));return event;
}
function click(target,detail=0,type='click'){const event=new MouseEvent(type,{bubbles:true,cancelable:true,detail});act(()=>target.dispatchEvent(event));return event;}
function preview(){return document.querySelector('[role="dialog"]');}
function close(){click(document.querySelector('[data-modal-close]'));}

test('mouse hover opens at 400ms, persists on leave and does not reopen until the pointer leaves the day',async()=>{
 const cell=await setup();pointer(cell,'pointerover');advance(399);assert.ok(preview()===null);advance(1);assert.ok(preview());
 pointer(cell,'pointerout',{relatedTarget:document.body});assert.ok(preview());close();
 pointer(cell,'pointerover');advance(1000);assert.ok(preview()===null);
 pointer(cell,'pointerout',{relatedTarget:document.body,clientX:200});pointer(cell,'pointerover');advance(400);assert.ok(preview());
 assert.equal(loads,1,'hover must not fetch entries');
});
test('leaving early, unsupported hover, empty days and unmount cancel pending previews',async()=>{
 const cell=await setup();pointer(cell,'pointerover');advance(399);pointer(cell,'pointerout',{relatedTarget:document.body});advance(1);assert.ok(preview()===null);
 hoverSupported=false;pointer(cell,'pointerover');advance(500);assert.ok(preview()===null);hoverSupported=true;
 const empty=[...document.querySelectorAll('[data-calendar-day]')].find(item=>item!==cell);
 pointer(empty,'pointerover');advance(600);pointer(empty,'pointerdown',{pointerType:'touch'});advance(600);assert.ok(preview()===null);
 pointer(cell,'pointerover');await settle(()=>root.unmount());root=null;advance(500);assert.equal(timers.size,0);
});
test('touch long press opens at 550ms and suppresses its context menu, click and double click even after a long hold',async()=>{
 const cell=await setup();assert.equal(click(cell,0,'contextmenu').defaultPrevented,false);
 pointer(cell,'pointerdown',{pointerType:'touch'});advance(549);assert.ok(preview()===null);advance(1);assert.ok(preview());advance(3000);
 assert.equal(click(cell,0,'contextmenu').defaultPrevented,true);
 pointer(cell,'pointerup',{pointerType:'touch'});assert.equal(click(cell,1).defaultPrevented,true);assert.equal(click(cell,2,'dblclick').defaultPrevented,true);
 assert.ok(document.querySelector('.calendar-day-preview'));assert.doesNotMatch(preview().textContent,/Neuer Kalendereintrag/);
});
test('movement, native scroll, pointer cancellation and early release cancel long press without preventing scroll',async()=>{
 const cell=await setup();
 for(const type of ['pointermove','scroll','pointercancel','pointerup']){
  pointer(cell,'pointerdown',{pointerType:'touch'});advance(300);
  const event=type==='scroll'?new Event('scroll',{bubbles:true,cancelable:true}):null;
  if(event)act(()=>window.dispatchEvent(event));else assert.equal(pointer(cell,type,{pointerType:'touch',clientY:40}).defaultPrevented,false);
  advance(600);assert.ok(preview()===null);if(event)assert.equal(event.defaultPrevented,false);
 }
});
test('visible action shows all five entries in stable time order, including closures, cancellation and missing information',async()=>{
 const cell=await setup();const action=[...cell.querySelectorAll('button')].find(item=>item.textContent.includes('Alle 5 ansehen'));
 action.focus();click(action);assert.ok(preview());
 assert.equal(preview().querySelector('h2').textContent,new Date(`${date}T12:00:00`).toLocaleDateString('de-DE',{weekday:'long',day:'2-digit',month:'2-digit',year:'numeric'}));
 assert.match(preview().textContent,/5 Veranstaltungen/);
 const rows=[...preview().querySelectorAll('.calendar-day-preview-entry')];assert.equal(rows.length,5);
 assert.match(rows[0].textContent,/21:00 · Frühe Party/);assert.match(rows[1].textContent,/21:00 · Zeitgleich/);assert.match(rows[2].textContent,/23:00 · Späte Party.*ABGESAGT/);assert.match(rows[3].textContent,/Rocknacht/);assert.match(rows[4].textContent,/Location-Sperre/);
 assert.doesNotMatch(rows[3].textContent,/Club|Unbekannte Location|00:00/);assert.equal(rows[3].style.borderLeftColor,'rgb(199, 49, 83)');
 assert.equal(cell.querySelectorAll('button button').length,0);assert.equal(cell.querySelectorAll('button[title]').length,2);
});
test('selection opens the existing event detail dialog and flyer; Escape and close restore focus',async()=>{
 const cell=await setup();catalog.entries[2].eventFlyer={url:'/event-flyer'};
 const action=[...cell.querySelectorAll('button')].find(item=>item.textContent.includes('Alle 5 ansehen'));action.focus();click(action);
 act(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})));assert.ok(preview()===null);assert.equal(document.activeElement,action);
 click(action);close();assert.ok(preview()===null);assert.equal(document.activeElement,action);
 click(action);click(document.querySelector('.calendar-day-preview-entry'));assert.equal(document.querySelectorAll('[role="dialog"]').length,1);assert.equal(preview().querySelector('h2').textContent,'Frühe Party');assert.equal(preview().querySelector('img').getAttribute('src'),'/event-flyer');
});
test('normal tap, double click and directly tapped event retain their existing behavior',async()=>{
 const cell=await setup();pointer(cell,'pointerdown',{pointerType:'touch'});advance(200);pointer(cell,'pointerup',{pointerType:'touch'});click(cell,1);assert.ok(preview()===null);assert.equal(cell.querySelector('[data-day-select]').getAttribute('aria-pressed'),'true');
 click(cell,2,'dblclick');assert.match(preview().textContent,/Neuer Kalendereintrag/);close();
 const event=cell.querySelector('button[title]');pointer(event,'pointerdown',{pointerType:'touch'});advance(600);assert.ok(preview()===null);pointer(event,'pointerup',{pointerType:'touch'});click(event,1);assert.match(preview().textContent,/Rocknacht/);
});
test('refreshing data invalidates an open preview or pending timer',async()=>{
 const cell=await setup();click([...cell.querySelectorAll('button')].find(item=>item.textContent.includes('Alle 5 ansehen')));
 catalog.entries=[...catalog.entries];await settle(()=>root.render(h(Page)));assert.ok(preview()===null);
 pointer(cell,'pointerover');catalog.entries=[];await settle(()=>root.render(h(Page)));advance(600);assert.ok(preview()===null);
});

function change(control,value,type='change'){
 act(()=>{Object.getOwnPropertyDescriptor(control.tagName==='SELECT'?dom.window.HTMLSelectElement.prototype:dom.window.HTMLInputElement.prototype,'value').set.call(control,value);control.dispatchEvent(new Event(type,{bubbles:true}));});
}
test('calendar type, location and search filters also apply to preview; filter and month changes close it',async()=>{
 const cell=await setup();
 const selects=document.querySelectorAll('select');
 change(selects[0],'closure');
 assert.match(cell.textContent,/Alle 1 ansehen/);assert.doesNotMatch(cell.textContent,/Rocknacht|Späte Party/);
 click([...cell.querySelectorAll('button')].find(item=>item.textContent.includes('Alle 1 ansehen')));assert.equal(document.querySelectorAll('.calendar-day-preview-entry').length,1);
 change(selects[1],'other');assert.ok(preview()===null);assert.doesNotMatch(cell.textContent,/Alle/);
 change(selects[1],'');change(selects[0],'all');change(document.querySelector('input[type="search"]'),'Frühe','input');
 assert.match(cell.textContent,/Alle 1 ansehen/);click([...cell.querySelectorAll('button')].find(item=>item.textContent.includes('Alle 1 ansehen')));assert.match(preview().textContent,/Frühe Party/);assert.doesNotMatch(preview().textContent,/Späte Party/);
 change(document.querySelector('input[type="search"]'),'','input');assert.ok(preview()===null);
 click([...cell.querySelectorAll('button')].find(item=>item.textContent.includes('Alle 5 ansehen')));
 click(document.querySelector('[aria-label="Nächster Monat"]'));assert.ok(preview()===null);
});
