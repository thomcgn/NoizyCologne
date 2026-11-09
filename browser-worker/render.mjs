import { chromium } from 'playwright';
import { BOT, publicUrl } from './security.mjs';

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
export async function render(url, { proxy, permit, timeout = 20000, launch = {}, prepare = async () => {} } = {}) {
  publicUrl(url);
  const warnings = new Set(), diagnostics = [], snapshots = [], seen = new Set();
  let browser, requests = 0, bytes = 0, active = 0;
  const waiting = [];
  const acquire = async () => {if(active<8) {active++;return;} await new Promise(resolve=>waiting.push(resolve));};
  const release = () => {const next=waiting.shift();if(next)next();else active--;};
  try {
    browser = await chromium.launch({ headless: true, chromiumSandbox: true, timeout: 10000,
      proxy, args: ['--disable-quic', '--proxy-bypass-list=<-loopback>', '--force-webrtc-ip-handling-policy=disable_non_proxied_udp'],
      env: { PATH: process.env.PATH, HOME: '/tmp', LANG: 'de_DE.UTF-8' }, ...launch });
    const context = await browser.newContext({ userAgent: BOT, serviceWorkers: 'block', acceptDownloads: false,
      locale: 'de-DE', timezoneId: 'Europe/Berlin', viewport: { width: 1000, height: 800 } });
    await context.routeWebSocket('**/*', socket => socket.close());
    await context.route('**/*', async route => {
      const request = route.request();
      if (['image', 'media', 'font'].includes(request.resourceType())) { await route.abort(); return; }
      let acquired=false;
      try {
        publicUrl(request.url());
        if (!['GET', 'HEAD'].includes(request.method())) throw new Error('REQUEST_METHOD_BLOCKED');
        if (++requests > 160) throw new Error('REQUEST_LIMIT');
        await acquire(); acquired=true;
        if (!await permit(request.url())) throw new Error('ROBOTS_BLOCKED');
        // Inspect redirects before Chromium can follow them without a second route callback.
        const response = await route.fetch({ maxRedirects: 0, timeout: 6000, headers:{...request.headers(),connection:'close'} });
        if (response.status() >= 300 && response.status() < 400) { await response.dispose(); throw new Error('REDIRECT_REQUIRES_REVIEW'); }
        if ([401, 403, 429].includes(response.status())) { await response.dispose(); throw new Error('BLOCKED'); }
        const body = await response.body(); bytes += body.length;
        if (body.length > 4 * 1024 * 1024 || bytes > 24 * 1024 * 1024) { await response.dispose(); throw new Error('RESPONSE_TOO_LARGE'); }
        const headers = { ...response.headers() };
        delete headers['content-encoding']; delete headers['content-length'];
        await route.fulfill({ status: response.status(), headers, body }); await response.dispose();
      } catch (error) {
        const code = ['UNSAFE_URL','REQUEST_METHOD_BLOCKED','REQUEST_LIMIT','ROBOTS_BLOCKED','REDIRECT_REQUIRES_REVIEW','BLOCKED','RESPONSE_TOO_LARGE'].includes(error.message) ? error.message : 'BROWSER_NETWORK_ERROR';
        warnings.add(code);
        // Keep bounded, actionable evidence without query credentials or arbitrary error bodies.
        if (warnings.size < 24) {
          try { const target = new URL(request.url()); warnings.add(`${code}: ${request.method()} ${request.resourceType()} ${target.origin}${target.pathname.slice(0,200)}${code==='UNSAFE_URL' ? ` (URL length ${request.url().length})` : ''}`); }
          catch { /* Malformed targets are already represented by UNSAFE_URL. */ }
        }
        await route.abort().catch(() => {});
      } finally { if(acquired)release(); }
    });
    await prepare(context);
    const page = await context.newPage();
    page.on('pageerror', () => warnings.add('JAVASCRIPT_ERROR'));
    page.on('dialog', dialog => dialog.dismiss().catch(() => {}));
    page.on('download', download => { warnings.add('DOWNLOAD_BLOCKED'); download.cancel().catch(() => {}); });
    context.on('page', other => { if (other !== page) { warnings.add('POPUP_BLOCKED'); other.close().catch(() => {}); } });
    const deadline = Date.now() + Math.max(3000, Math.min(timeout, 30000));
    const settle = async () => {
      const remaining = deadline - Date.now();
      if (remaining < 1) throw new Error('BROWSER_TIMEOUT');
      await page.waitForLoadState('networkidle', { timeout: Math.min(1800, remaining) }).catch(() => warnings.add('ASYNC_CONTENT_UNSETTLED'));
      await delay(Math.min(350, Math.max(0, deadline - Date.now())));
    };
    const snapshot = async () => {
      for (const frame of page.frames().slice(0, 5)) {
        if (frame.url() === 'about:blank') continue;
        try {
          publicUrl(frame.url());
          // Store event DOM, not repeated executable bundles/CSS. JSON-LD and hidden
          // panels remain intact; the live page and its security policy are unchanged.
          const body = await frame.evaluate(() => {
            const copy=document.documentElement.cloneNode(true);
            copy.querySelectorAll('script:not([type="application/ld+json"]),style,svg').forEach(node=>node.remove());
            return '<!doctype html>'+copy.outerHTML;
          }), key = frame.url() + body;
          if (seen.has(key)) continue;
          if (Buffer.byteLength(snapshots.map(item => item.body).join('') + body) > 1500000) { warnings.add('DOM_LIMIT'); continue; }
          seen.add(key); snapshots.push({ url: frame.url(), body, contentType: 'text/html' });
        } catch { warnings.add('FRAME_INCOMPLETE'); }
      }
      if (page.frames().length > 5) warnings.add('FRAME_LIMIT');
    };
    let collapsiblesFound=0, collapsiblesOpened=0, paginationSteps=0;
    const inspected=new Set();
    const expand=async () => {
      // Read server-rendered/hidden DOM before interacting. Never expand navigation menus,
      // consent dialogs, forms or arbitrary links. Missing panels may be loaded on demand.
      const controls=page.locator('button[aria-expanded="false"], [role="button"][aria-expanded="false"], details:not([open]) > summary');
      const handles=await controls.elementHandles();
      for(const control of handles.slice(0,40)) {
        if(Date.now()>=deadline-1500) { warnings.add('ACCORDION_LIMIT'); break; }
        const info=await control.evaluate(element=>{
          if(element.closest('nav,header,footer,form,[role="dialog"]'))return null;
          let card=element.closest('article,li,[role="listitem"],details');
          if(!card) for(let node=element.parentElement,depth=0;node && depth<5;node=node.parentElement,depth++) {
            if(node.querySelector('time,[itemprop="startDate"]') || /\d{1,2}\.\s*(?:\d{1,2}\.|[A-Za-z]+)|\d{4}-\d{2}-\d{2}/.test(node.textContent)) {card=node;break;}
          }
          if(!card)return null;
          const href=element.getAttribute('href');
          if(href && !href.startsWith('#'))return null;
          const target=element.tagName==='SUMMARY'?element.parentElement:document.getElementById(element.getAttribute('aria-controls')) || card.querySelector('[role="region"],[hidden]');
          const available=target && (element.tagName==='SUMMARY'?[...target.children].filter(child=>child!==element).map(child=>child.textContent).join(' '):target.textContent).trim();
          return {key:element.id || element.getAttribute('aria-controls') || card.textContent.slice(0,160),available:Boolean(available)};
        }).catch(()=>null);
        if(!info || inspected.has(info.key))continue;
        inspected.add(info.key);collapsiblesFound++;
        if(info.available)continue;
        if(collapsiblesOpened>=8) {warnings.add('ACCORDION_LIMIT');continue;}
        if(!await control.isVisible() || !await control.isEnabled()) {warnings.add('ACCORDION_UNAVAILABLE');continue;}
        const before=page.url();
        await control.click({timeout:1200}).then(()=>collapsiblesOpened++).catch(()=>warnings.add('ACCORDION_INTERACTION_FAILED'));
        await settle();await snapshot();
        if(page.url()!==before) {warnings.add('ACCORDION_NAVIGATION');break;}
      }
      if(handles.length>40)warnings.add('ACCORDION_LIMIT');
      await Promise.all(handles.map(handle=>handle.dispose()));
    };
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: Math.min(12000, timeout) });
    await settle(); await snapshot();
    const title = (await page.title()).toLowerCase();
    if (/captcha|just a moment|access denied|verify.*human/.test(title)) throw new Error('BLOCKED');
    await expand();
    let previousHeight=0;
    for (let step = 0; step < 2 && Date.now() < deadline - 1000; step++) {
      const height=await page.evaluate(() => {window.scrollBy(0, Math.min(document.body.scrollHeight,1200));return document.body.scrollHeight;});
      await settle(); await snapshot();await expand();
      if(step===1 && height>previousHeight && height>2000)warnings.add('LAZY_LOADING_LIMIT');
      previousHeight=height;
    }
    if(await page.evaluate(() => window.scrollY+window.innerHeight<document.body.scrollHeight-32))warnings.add('LAZY_LOADING_LIMIT');
    const navigationName=/^(mehr (veranstaltungen|events|laden)|load more(?: events)?|next(?: month| page)?|nächste[rns]? (monat|seite)|weiter)$/i;
    const navigation = page.getByRole('button',{name:navigationName}).or(page.getByRole('link',{name:navigationName})).or(page.locator('a[rel="next"]'));
    for (let step = 0; step < 2 && Date.now() < deadline - 2500; step++) {
      let next;
      for(const candidate of await navigation.elementHandles()) {
        if(await candidate.isVisible() && await candidate.isEnabled() && await candidate.evaluate(element=>!element.closest('form,[role="dialog"]'))) {next=candidate;break;}
      }
      if(!next)break;
      const before=await page.content();
      await next.click({ timeout: 1500 });paginationSteps++;await settle();await snapshot();await expand();
      if(await page.content()===before) {warnings.add('PAGINATION_NO_PROGRESS');break;}
    }
    let further=false;
    for(const candidate of await navigation.elementHandles()) if(await candidate.isVisible() && await candidate.isEnabled() && await candidate.evaluate(element=>!element.closest('form,[role="dialog"]'))) {further=true;break;}
    if(further)warnings.add('PAGINATION_LIMIT');
    diagnostics.push(`COLLAPSIBLE_ELEMENTS_FOUND: ${collapsiblesFound}`,`COLLAPSIBLE_ELEMENTS_OPENED: ${collapsiblesOpened}`,
      `PAGINATION_STEPS: ${paginationSteps}`,`FURTHER_PAGES_UNPROCESSED: ${further}`);
    if (Date.now() >= deadline) warnings.add('BROWSER_TIMEOUT');
    return { status: warnings.size ? 'BROWSER_PARTIAL' : 'RENDERED', pages: snapshots, warnings: [...diagnostics,...warnings], complete: false };
  } catch (error) {
    const status = ['BLOCKED', 'BROWSER_TIMEOUT'].includes(error.message) ? error.message : error.name === 'TimeoutError' ? 'BROWSER_TIMEOUT' : 'BROWSER_FAILED';
    return { status, pages: snapshots, warnings: [...diagnostics,...warnings, status], complete: false };
  } finally { if (browser) await browser.close(); }
}
