import { test } from 'node:test';
import assert from 'node:assert/strict';
import { render } from '../../render.mjs';

const pages={
  'https://fixture.example/react': `<html><title>React Club</title><div id="root"></div><script>setTimeout(()=>document.getElementById('root').innerHTML='<div class="unrelated-card"><h2>NEON PARADISE VS. NEON KULT VOL: 2 THE REVENGE</h2><p>07. November 2026 23:00</p><a href="/event/neon">Details</a></div>',180)</script></html>`,
  'https://fixture.example/widgets': `<html><title>Calendar Widget</title><main id="list"></main><button id="more">Mehr Veranstaltungen</button><script>let page=0; const add=()=>{document.getElementById('list').innerHTML+='<article><h2>Indie Party '+(++page)+'</h2><time datetime="2026-11-0'+(6+page)+'T23:00"></time></article>';if(page===3)document.getElementById('more').disabled=true;};setTimeout(add,150);document.getElementById('more').onclick=()=>setTimeout(add,100);window.addEventListener('scroll',()=>{if(!document.getElementById('lazy')){const e=document.createElement('article');e.id='lazy';e.innerHTML='<h2>Lazy Emo Night</h2><time datetime="2026-11-12"></time>';document.body.append(e);}});document.body.style.minHeight='2500px';</script></html>`,
  'https://fixture.example/broken': '<html><title>Broken Calendar</title><div id="root"></div><script>throw new Error("Fixture error")</script></html>',
  'https://fixture.example/iframe': '<html><title>Public widget</title><iframe src="https://widget.example/calendar"></iframe></html>',
  'https://widget.example/calendar': '<article><h2>Unknown Emo Night</h2><time datetime="2026-11-07"></time></article>',
};
const prepare=async context => {
  await context.route('https://**/*',async route => {
    const body=pages[route.request().url()];
    if(body)await route.fulfill({status:200,contentType:'text/html',body});else await route.abort();
  });
};
test('real sandboxed Chromium runs delayed React-style cards with no location selectors',async () => {
  const result=await render('https://fixture.example/react',{permit:async()=>true,prepare});
  assert.equal(result.status,'RENDERED',JSON.stringify(result));
  assert.ok(result.pages.map(p=>p.body).join('').includes('NEON PARADISE VS. NEON KULT VOL: 2 THE REVENGE'));
  assert.equal(result.complete,false);
});
test('same renderer supports a different layout, lazy loading and bounded pagination',async () => {
  const result=await render('https://fixture.example/widgets',{permit:async()=>true,prepare});
  assert.equal(result.status,'RENDERED',JSON.stringify(result));
  const dom=result.pages.map(p=>p.body).join('');
  for(const name of ['Indie Party 1','Indie Party 2','Indie Party 3','Lazy Emo Night'])assert.match(dom,new RegExp(name));
});
test('public iframe DOM is captured with its own source URL',async () => {
  const result=await render('https://fixture.example/iframe',{permit:async()=>true,prepare});
  assert.ok(result.pages.some(p=>p.url==='https://widget.example/calendar'&&p.body.includes('Unknown Emo Night')),JSON.stringify(result));
});
test('broken JavaScript is an incomplete render, never a confirmed empty calendar',async () => {
  const result=await render('https://fixture.example/broken',{permit:async()=>true,prepare});
  assert.equal(result.status,'BROWSER_PARTIAL');assert.ok(result.warnings.includes('JAVASCRIPT_ERROR'));assert.equal(result.complete,false);
});
test('denied requests and timeouts remain explicit failures',async () => {
  const denied=await render('https://denied.example/calendar',{permit:async()=>false});
  assert.notEqual(denied.status,'RENDERED');assert.ok(denied.warnings.includes('ROBOTS_BLOCKED'));
  const timeout=await render('https://timeout.example/calendar',{timeout:1000,permit:async()=>true,prepare:async context=>{
    await context.route('https://timeout.example/**',async()=>{await new Promise(resolve=>setTimeout(resolve,3000));});
  }});
  assert.equal(timeout.status,'BROWSER_TIMEOUT');assert.equal(timeout.complete,false);
});

test('request diagnostics preserve blocked method and source without leaking query values',async()=>{
  const result=await render('https://fixture.example/diagnostic',{permit:async()=>true,prepare:async context=>{
    await context.route('https://fixture.example/diagnostic',route=>route.fulfill({status:200,contentType:'text/html',body:'<title>Fixture</title><script>fetch("https://fixture.example/api?token=private-fixture-value",{method:"POST"}).catch(()=>{});</script>'}));
  }});
  assert.equal(result.status,'BROWSER_PARTIAL');
  assert.ok(result.warnings.includes('REQUEST_METHOD_BLOCKED'));
  assert.ok(result.warnings.some(warning=>warning.includes('POST fetch https://fixture.example/api')));
  assert.ok(result.warnings.every(warning=>!warning.includes('private-fixture-value')));
});

test('inert embedded JSON survives snapshots while executable scripts do not',async()=>{
  const result=await render('https://fixture.example/embedded',{permit:async()=>true,prepare:async context=>{
    await context.route('https://fixture.example/embedded',route=>route.fulfill({status:200,contentType:'text/html',body:`<title>Embedded</title><div id="root"></div>
      <script type="application/ld+json">{"@type":"Event","name":"Structured Night","startDate":"2027-01-09"}</script>
      <script>window.__INITIAL_STATE__ = {"events":[{"title":"State Night","date":"2027-01-10T23:00"}]};</script>
      <script>window.dangerousFixtureMarker = true;</script>`}));
  }});
  const dom=result.pages.map(page=>page.body).join('');
  assert.match(dom,/data-noizy-embedded="application\/ld\+json"/);
  assert.match(dom,/data-noizy-embedded="assignment:__INITIAL_STATE__"/);
  assert.match(dom,/State Night/);
  assert.doesNotMatch(dom,/dangerousFixtureMarker/);
  assert.equal(result.complete,false);
});

test('telemetry GETs are discarded before a fetch while robots still gates real subrequests',async()=>{
  const inspected=[];
  const result=await render('https://fixture.example/telemetry',{permit:async url=>{inspected.push(url);return true;},prepare:async context=>{
    await context.route('https://fixture.example/telemetry',route=>route.fulfill({status:200,contentType:'text/html',body:
      '<title>Telemetry</title><article><h2>Party</h2><time datetime="2026-11-14T23:00"></time></article><script>fetch("https://frog.wix.com/pixel?utm_source=x").catch(()=>{});fetch("https://other.example/data").catch(()=>{});</script>'}));
  }});
  assert.ok(result.warnings.includes('EGRESS_DEPRIORITIZED'),JSON.stringify(result.warnings));
  // The real subrequest still reaches the robots gate; the discarded one never does.
  assert.ok(inspected.some(url=>url.includes('other.example/data')));
  assert.ok(inspected.every(url=>!url.includes('frog.wix.com')));
  assert.match(result.pages.map(page=>page.body).join(''),/Party/);
  assert.equal(result.complete,false);
});

test('accordion data is read before clicking; only missing event panels are expanded',async()=>{
  const result=await render('https://fixture.example/accordion',{permit:async()=>true,prepare:async context=>{
    await context.route('https://fixture.example/accordion',route=>route.fulfill({status:200,contentType:'text/html',body:`<title>Events</title>
      <nav><button aria-expanded="false" onclick="document.body.dataset.unsafe='clicked'">Menu</button></nav>
      <article><h2>NO CIGAR</h2><time datetime="2026-11-12T19:00"></time><button aria-expanded="false" aria-controls="cigar" onclick="document.body.dataset.unnecessary='clicked'">Infos</button><div id="cigar" hidden>Einlass: 19:00 Uhr · Beginn: 20:00 Uhr</div></article>
      <article><h2>PULZ. x lieb</h2><time datetime="2026-11-14T23:00"></time><button aria-expanded="false" aria-controls="pulz" onclick="this.setAttribute('aria-expanded','true');setTimeout(()=>document.getElementById('pulz').innerHTML='<p>Dynamisch geladene Beschreibung</p>',80)">Beschreibung öffnen</button><div id="pulz"></div><a href="/events/pulz">Erfahre hier mehr.</a></article>`}));
  }});
  const dom=result.pages.map(page=>page.body).join('');
  assert.match(dom,/Einlass: 19:00/);assert.match(dom,/Dynamisch geladene Beschreibung/);
  assert.doesNotMatch(dom,/data-(unsafe|unnecessary)="clicked"/);
  assert.ok(result.warnings.includes('COLLAPSIBLE_ELEMENTS_FOUND: 2'));
  assert.ok(result.warnings.includes('COLLAPSIBLE_ELEMENTS_OPENED: 1'));
});

test('load more stops on no progress and remaining pages are explicit',async()=>{
  const result=await render('https://fixture.example/stuck',{permit:async()=>true,prepare:async context=>{
    await context.route('https://fixture.example/stuck',route=>route.fulfill({status:200,contentType:'text/html',body:'<title>Events</title><article><h2>BEATSxBASSxWEEKEND SPECIAL</h2><time datetime="2026-11-13T23:00"></time></article><button>Mehr laden</button>'}));
  }});
  assert.ok(result.warnings.includes('PAGINATION_NO_PROGRESS'));
  assert.ok(result.warnings.includes('FURTHER_PAGES_UNPROCESSED: true'));
  assert.ok(result.warnings.includes('PAGINATION_STEPS: 1'));assert.equal(result.complete,false);
});
