import assert from 'node:assert/strict';
import {visualSession} from './fixtures/authSession.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture,mockControlCenterApi} from './fixtures/controlCenter.mjs';
import {candidate} from './fixtures/discovery.mjs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE??'playwright');
const origin=process.env.DISCOVERY_VISUAL_URL??'http://127.0.0.1:3107';
assert.ok(['localhost','127.0.0.1'].includes(new URL(origin).hostname));
const out=resolve(process.env.CC_OUTPUT??'.quality/discovery-geography');await mkdir(out,{recursive:true});
const {token, contextOptions} = await visualSession(origin);
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH??'/usr/bin/google-chrome-stable',headless:true});
const results=[];
try {
 for(const width of [1440,390,320]) {
  const context=await browser.newContext({...contextOptions, viewport:{width,height:1000},isMobile:width<768,hasTouch:width<768});
  await context.addCookies([{name:'noizy_team_session',value:token,url:origin}]);
  const data=controlCenterFixture();data.venues[0].name='Blue Shell';
  const local={...candidate('cologne','Kölner Party'),party_id:'party-0',special_source:true,geo_status:'INSIDE',status:'NEEDS_REVIEW',venue_id:'venue-0'};
  data.discovery.candidates=[local,{...candidate('wrong','Köln im Titel, Veranstaltung in Bonn'),special_source:true,geo_status:'OUTSIDE',status:'NEEDS_REVIEW'}];
  const unknown={id:'unknown-room',title:'Unbekannter Raum – Standort noch nicht belegt',source_url:'https://qrushapp.de/events/room',status:'UNRESOLVED',reason:'Keine Veranstaltungsstadt belegt',location:'Neuer Raum',place:{city:'',street:''}};
  const geography={items:[unknown,...['Bonn','Aachen','Berlin','Frankfurt'].map(city=>({id:city,title:`Party ${city}`,source_url:'https://portal.example/event',status:'OUTSIDE',location:city==='Bonn'?'Gleis8':'Club',reason:'Außerhalb Kölns',place:{city,street:'Straße 12'}}))],counts:{UNRESOLVED:1,OUTSIDE:4,NOT_CHECKED:0}};
  const calls=[];await mockControlCenterApi(context,data,{onRequest:request=>calls.push(request)});
  await context.route('**/api/v1/discovery/geography',route=>route.fulfill({json:geography}));
  let attempts=0;
  await context.route('**/api/v1/discovery/geography/unknown-room/confirm',async route=>{
   attempts++;const request=route.request(),body=request.postDataJSON();calls.push({path:new URL(request.url()).pathname,method:request.method()});
   assert.equal(body.venueId,'venue-0');assert.equal(body.city,'Köln');assert.equal(body.street,'Luxemburger Straße 32');assert.equal(body.confirmed,true);
   if(attempts===1)return route.fulfill({status:409,json:{message:'Testkonflikt: Ortsbelege erneut prüfen'}});
   geography.items=geography.items.filter(item=>item.id!==unknown.id);geography.counts.UNRESOLVED=0;
   data.discovery.candidates.push({...local,id:'confirmed-geo',title:unknown.title});
   await route.fulfill({status:204});
  });
  const page=await context.newPage();page.setDefaultTimeout(15000);const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(`${origin}/dashboard/vorschlaege`);
  const scope=page.locator('[aria-label="Standortklärung"]');await scope.getByRole('button',{name:'Standort klären',exact:true}).waitFor();
  assert.equal(await page.locator('[data-record-id="wrong"]').count(),0);assert.equal(await page.locator('[data-record-id="cologne"]').count(),1);
  await scope.getByRole('button',{name:'Standort klären',exact:true}).click();const dialog=page.getByRole('dialog');
  assert.equal(await dialog.getByRole('button',{name:'Kölner Standort bestätigen',exact:true}).isDisabled(),true);
  await dialog.getByLabel('Location',{exact:false}).selectOption('venue-0');await dialog.getByLabel('Straße und Hausnummer',{exact:true}).fill('Luxemburger Straße 32');
  await dialog.getByRole('checkbox').check();await dialog.getByRole('button',{name:'Kölner Standort bestätigen',exact:true}).click();
  await dialog.getByRole('alert').filter({hasText:'Testkonflikt'}).waitFor();assert.equal(await dialog.getByLabel('Straße und Hausnummer',{exact:true}).inputValue(),'Luxemburger Straße 32');
  await page.screenshot({path:`${out}/${width}-confirmation.png`});
  await dialog.getByRole('button',{name:'Kölner Standort bestätigen',exact:true}).click();await dialog.waitFor({state:'detached'});
  await page.locator('[data-record-id="confirmed-geo"]').waitFor();await scope.getByText('Keine ungeklärten Standortfunde.',{exact:true}).waitFor();
  await scope.locator('summary').filter({hasText:'Außerhalb Kölns'}).click();await scope.getByText('Gleis8 · Bonn',{exact:true}).waitFor();
  assert.equal(await scope.getByRole('button',{name:'Standort klären',exact:true}).count(),0);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:`${out}/${width}-separation.png`});
  assert.deepEqual(errors,[]);assert.equal(attempts,2);assert.ok(calls.filter(call=>call.method!=='GET').every(call=>call.path.endsWith('/geography/unknown-room/confirm')));
  results.push({width,passed:true});await context.close();
 }
}finally{await browser.close();}
await writeFile(`${out}/results.json`,JSON.stringify(results,null,2)+'\n');
console.log(`${results.length} synthetic geography separation and explicit-confirmation scenarios passed`);
