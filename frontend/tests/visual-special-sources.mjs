import assert from 'node:assert/strict';
import {visualSession} from './fixtures/authSession.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {controlCenterFixture,mockControlCenterApi} from './fixtures/controlCenter.mjs';
import {candidate} from './fixtures/discovery.mjs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE??'playwright');
const origin=process.env.DISCOVERY_VISUAL_URL??'http://127.0.0.1:3107';
assert.ok(['localhost','127.0.0.1'].includes(new URL(origin).hostname));
const out=resolve(process.env.CC_OUTPUT??'.quality/special-sources');await mkdir(out,{recursive:true});
const {token, contextOptions} = await visualSession(origin);
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH??'/usr/bin/google-chrome-stable',headless:true});
const results=[];
try {
  for(const [width,theme] of [[1440,'graphite'],[390,'light'],[320,'midnight']]) {
    const context=await browser.newContext({...contextOptions, viewport:{width,height:1000},isMobile:width<768,hasTouch:width<768});
    await context.addCookies([{name:'noizy_team_session',value:token,url:origin}]);
    await context.addInitScript(theme=>localStorage.setItem('noizy-theme',theme),theme);
    const data=controlCenterFixture();data.venues[0].name='Blue Shell';
    const proposal={...candidate('portal','Karnevalsnacht – bereits am Titel erkennbar unpassend'),special_source:true,venue_id:null,venue_name:'Veranstaltungsort offen',reported_location:'Blue Shell',source_name:'QRUSH',party_id:'party-0',status:'NEEDS_REVIEW',assessment:{recognition:{status:'SINGLE',parties:[]},verification:{status:'VERIFIED',date:'2027-01-09',reasons:[]},lifecycle:'ACTIVE',automaticImportEligible:false,importBlockers:[]}};
    data.discovery.candidates=[proposal];data.discovery.sources=[{id:'location-source',venue_id:'venue-0',venue_name:'Versteckte Location-Quelle',url:'https://hidden.example/',source_type:'OFFICIAL',status:'FAILED',quality:'FAILED'}];
    const calls=[];await mockControlCenterApi(context,data,{onRequest:request=>calls.push(request)});
    await context.route('**/api/v1/discovery/keyword-rules',route=>route.fulfill({json:[]}));
    await context.route('**/api/v1/discovery/special-sources',async route=>{
      const body=route.request().postDataJSON();assert.equal(body.name,'QRUSH');assert.equal(body.url,'https://qrushapp.de/');assert.equal(body.venueId,undefined);
      calls.push({path:'/api/v1/discovery/special-sources',method:'POST'});
      data.discovery.sources=[{id:'qrush',name:'QRUSH',special_source:true,venue_id:null,venue_name:'',url:body.url,source_type:'EXTERNAL',source_kind:'PLATFORM',approved:false,status:'NOT_CHECKED',item_selector:'',title_selector:'',date_selector:''}];
      await route.fulfill({json:{id:'qrush'}});
    });
    await context.route('**/api/v1/discovery/special-sources/qrush',async route=>{
      const request=route.request();calls.push({path:'/api/v1/discovery/special-sources/qrush',method:request.method()});
      if(request.method()==='PUT') {
        const body=request.postDataJSON();assert.equal(body.name,'QRUSH Köln');assert.equal(body.url,'https://qrushapp.de/eventfinder/koeln');
        Object.assign(data.discovery.sources[0],{name:body.name,url:body.url});
      }else{assert.equal(request.method(),'DELETE');data.discovery.sources=[];}
      await route.fulfill({status:204});
    });
    let rejectionAttempts=0;
    await context.route('**/api/v1/discovery/candidates/reject-batch',async route=>{
      calls.push({path:'/api/v1/discovery/candidates/reject-batch',method:route.request().method()});rejectionAttempts++;
      if(rejectionAttempts===1) return route.fulfill({status:503,json:{message:'Testfehler: Bitte erneut versuchen'}});
      proposal.status='REJECTED';proposal.decision_origin='MANUAL';await route.fulfill({json:{requested:1,rejected:1,alreadyRejected:0,rejectedIds:['portal'],alreadyRejectedIds:[],conflicts:[]}});
    });
    const page=await context.newPage();page.setDefaultTimeout(15000);const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.goto(`${origin}/dashboard/vorschlaege`);
    assert.equal(await page.getByText('Versteckte Location-Quelle',{exact:false}).count(),0);
    assert.equal(await page.getByRole('heading',{name:'Location-Quellen',exact:true}).count(),0);
    await page.getByRole('button',{name:'Veranstaltung prüfen',exact:true}).click();
    let dialog=page.getByRole('dialog');const venue=dialog.getByLabel('Veranstaltungsort zuordnen',{exact:false});
    assert.equal(await venue.inputValue(),'');assert.equal(await dialog.getByRole('button',{name:'Bestätigen & übernehmen',exact:true}).isDisabled(),true);
    await venue.selectOption('venue-0');assert.equal(await dialog.getByRole('button',{name:'Bestätigen & übernehmen',exact:true}).isDisabled(),false);
    await page.screenshot({path:`${out}/${theme}-${width}-venue.png`});
    page.once('dialog',dialog=>dialog.accept());await page.keyboard.press('Escape');await dialog.waitFor({state:'detached'});
    await page.locator('.discovery-tools > summary').click();
    await page.getByRole('button',{name:'Sonderquelle hinzufügen',exact:true}).click();dialog=page.getByRole('dialog');
    assert.equal(await dialog.getByLabel('Location',{exact:true}).count(),0);
    await dialog.getByLabel('Name der Sonderquelle',{exact:true}).fill('QRUSH');await dialog.getByLabel('Quell-URL',{exact:true}).fill('https://qrushapp.de/');
    await page.screenshot({path:`${out}/${theme}-${width}-add.png`});
    await dialog.getByRole('button',{name:'Speichern',exact:true}).click();await dialog.waitFor({state:'detached'});
    await page.getByRole('button',{name:'Sonderquelle löschen',exact:true}).waitFor();
    await page.screenshot({path:`${out}/${theme}-${width}-configured.png`});
    await page.getByRole('button',{name:'Sonderquelle editieren',exact:true}).click();dialog=page.getByRole('dialog');
    assert.equal(await dialog.getByLabel('Name der Sonderquelle',{exact:true}).inputValue(),'QRUSH');
    await dialog.getByLabel('Name der Sonderquelle',{exact:true}).fill('QRUSH Köln');
    await dialog.getByLabel('Quell-URL',{exact:true}).fill('https://qrushapp.de/eventfinder/koeln');
    await page.screenshot({path:`${out}/${theme}-${width}-edit.png`});
    await dialog.getByRole('button',{name:'Speichern',exact:true}).click();await dialog.waitFor({state:'detached'});
    await page.locator('[aria-label="Sonderquellen"]').getByText('QRUSH Köln',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Sonderquelle löschen',exact:true}).click();await page.getByText('Noch keine Sonderquellen hinterlegt.',{exact:true}).waitFor();
    assert.equal(await page.locator('[data-record-id="portal"]').count(),1);
    await page.getByRole('button',{name:'Ablehnung vormerken',exact:true}).click();
    await page.locator('[data-record-id="portal"]').waitFor({state:'detached'});
    await page.getByRole('button',{name:'Ablehnen',exact:true}).click();
    const reject=page.getByRole('dialog').getByRole('button',{name:'1 Veranstaltungen ablehnen',exact:true});
    await reject.click();await page.getByRole('dialog').getByRole('alert').filter({hasText:'Testfehler'}).waitFor();
    await page.screenshot({path:`${out}/${theme}-${width}-reject-failed.png`});
    await reject.click();await page.getByText('1 Veranstaltungen abgelehnt',{exact:false}).waitFor();
    assert.equal(await page.getByRole('dialog').count(),0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:`${out}/${theme}-${width}-rejected.png`});
    assert.deepEqual(errors,[]);assert.equal(rejectionAttempts,2);
    assert.ok(calls.filter(call=>call.method!=='GET').every(call=>/special-sources|\/reject-batch$/.test(call.path)));
    results.push({width,theme,passed:true});await context.close();
  }
}finally{await browser.close();}
await writeFile(`${out}/results.json`,JSON.stringify(results,null,2)+'\n');
console.log(`${results.length} synthetic special-source add/edit/delete, hidden location sources, explicit venue and instant-rejection scenarios passed`);
