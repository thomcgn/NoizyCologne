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
