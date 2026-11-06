import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture, mockControlCenterApi} from './fixtures/controlCenter.mjs';
import {candidate} from './fixtures/discovery.mjs';

const {chromium}=await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const origin=process.env.DISCOVERY_VISUAL_URL ?? 'http://127.0.0.1:3107';
assert.ok(['localhost','127.0.0.1'].includes(new URL(origin).hostname));
const secret=process.env.DISCOVERY_VISUAL_SECRET;assert.ok(secret);
const out=resolve(process.env.CC_OUTPUT ?? '.quality/discovery-confirmation');await mkdir(out,{recursive:true});
const now=Math.floor(Date.now()/1000);
const payload=Buffer.from(JSON.stringify({sub:'confirmation-fixture',iat:now,exp:now+3600,nonce:'synthetic-only'})).toString('base64url');
const token=`${payload}.${createHmac('sha256',secret).update(payload).digest('base64url')}`;
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH ?? '/usr/bin/google-chrome-stable',headless:true});
const results=[];
try {
  for (const [width,theme] of [[1440,'graphite'],[390,'light'],[320,'midnight']]) {
    const context=await browser.newContext({viewport:{width,height:1000},isMobile:width<768,hasTouch:width<768});
    await context.addCookies([{name:'noizy_team_session',value:token,url:origin}]);
    await context.addInitScript(theme=>localStorage.setItem('noizy-theme',theme),theme);
    const data=controlCenterFixture();data.parties[0].name='Middance Crisis';data.venues[0].name='Blue Shell';
    const proposal={...candidate('middance','Middance Crisis – Anti-Herbstblues-Special'),party_id:'party-0',event_date:'2027-01-09',venue_name:'Blue Shell',matching_class:'MATCH',status:'NEEDS_REVIEW',assessment:{recognition:{status:'SINGLE',parties:[{id:'party-0',name:'Middance Crisis',matchedName:'Middance Crisis'}]},verification:{status:'VERIFIED',date:'2027-01-09',reasons:[]},lifecycle:'ACTIVE',automaticImportEligible:false,importBlockers:[]},genreCoverage:{matched:1,total:1,percent:100,names:['Indie'],eligible:true,sourceNames:[],standardNames:['Indie'],basis:'STANDARD'}};
    data.discovery.candidates=[proposal];
    const calls=[];await mockControlCenterApi(context,data,{onRequest:request=>calls.push(request)});
    let attempts=0;
    await context.route('**/api/v1/discovery/candidates/middance/confirm',async route=>{
      attempts++;const request=route.request();calls.push({path:new URL(request.url()).pathname,method:request.method()});
      if (attempts===1) return route.fulfill({status:409,contentType:'application/json',body:JSON.stringify({message:'Ganztägige Location-Sperre am bestätigten Datum'})});
      const body=request.postDataJSON();assert.equal(body.seriesAlias,null);
      proposal.status='IMPORTED';proposal.calendar_entry_id='confirmed-entry';
      data.entries.push({id:'confirmed-entry',type:'event',partyId:body.partyId,locationId:'venue-0',date:body.date,startTime:'',entryPrice:'',notes:'Bestätigte Quelle',mentionDjIds:[],eventFlyer:null,eventStatus:'ACTIVE'});
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({calendarEntryId:'confirmed-entry'})});
    });
    const page=await context.newPage();page.setDefaultTimeout(15000);const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.goto(`${origin}/dashboard/vorschlaege`);
    await page.getByRole('button',{name:'Veranstaltung prüfen',exact:true}).click();
    const dialog=page.getByRole('dialog');
    await dialog.getByRole('button',{name:'Bestätigen & übernehmen',exact:true}).click();
    await dialog.getByRole('alert').filter({hasText:'Ganztägige Location-Sperre'}).waitFor();
    assert.equal(await dialog.locator('input[type="date"]').inputValue(),'2027-01-09');
    assert.equal(await page.getByText('Termin bestätigt und in den Kalender übernommen.',{exact:false}).count(),0);
    await page.screenshot({path:`${out}/${theme}-${width}-conflict.png`});
    await dialog.getByRole('button',{name:'Bestätigen & übernehmen',exact:true}).click();
    await dialog.waitFor({state:'detached'});
    await page.getByText('Termin bestätigt und in den Kalender übernommen.',{exact:false}).waitFor();
    assert.equal(await page.locator('[data-record-id="middance"]').count(),0);
    assert.ok(calls.filter(call=>call.path==='/api/v1/calendar-entries').length>=2);
    assert.ok(calls.filter(call=>call.path==='/api/v1/editorial-drafts').length>=2);
    await page.goto(`${origin}/dashboard/kalender#record=confirmed-entry`);
    await page.getByRole('dialog').getByRole('heading',{name:'Middance Crisis',exact:true}).waitFor();
    await page.screenshot({path:`${out}/${theme}-${width}-calendar.png`});
    await page.keyboard.press('Escape');
    await page.goto(`${origin}/dashboard/redaktion#record=confirmed-entry`);
    await page.locator('#editorial-editor').getByRole('textbox',{name:'Caption',exact:true}).waitFor({state:'visible'});
    assert.match(await page.locator('#editorial-editor > [role="status"]').innerText(),/Middance Crisis/);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:`${out}/${theme}-${width}-editorial.png`});
    assert.deepEqual(errors,[]);assert.equal(attempts,2);
    assert.ok(calls.filter(call=>call.method!=='GET').every(call=>call.path.endsWith('/confirm')));
    results.push({width,theme,passed:true});await context.close();
  }
} finally {await browser.close();}
await writeFile(`${out}/results.json`,JSON.stringify(results,null,2)+'\n');
console.log(`${results.length} synthetic confirmation → calendar → editorial scenarios passed`);
