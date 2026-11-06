import { chromium } from 'playwright';
import { BOT, publicUrl } from './security.mjs';

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
export async function render(url, { proxy, permit, timeout = 20000, launch = {}, prepare = async () => {} } = {}) {
  publicUrl(url);
  const warnings = new Set(), snapshots = [], seen = new Set();
  let browser, requests = 0, bytes = 0;
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
      try {
        publicUrl(request.url());
        if (!['GET', 'HEAD'].includes(request.method())) throw new Error('REQUEST_METHOD_BLOCKED');
        if (++requests > 160) throw new Error('REQUEST_LIMIT');
        if (!await permit(request.url())) throw new Error('ROBOTS_BLOCKED');
        // Inspect redirects before Chromium can follow them without a second route callback.
        const response = await route.fetch({ maxRedirects: 0, timeout: 6000 });
        if (response.status() >= 300 && response.status() < 400) { await response.dispose(); throw new Error('REDIRECT_REQUIRES_REVIEW'); }
        if ([401, 403, 429].includes(response.status())) { await response.dispose(); throw new Error('BLOCKED'); }
        const body = await response.body(); bytes += body.length;
        if (body.length > 4 * 1024 * 1024 || bytes > 24 * 1024 * 1024) { await response.dispose(); throw new Error('RESPONSE_TOO_LARGE'); }
        const headers = { ...response.headers() };
        delete headers['content-encoding']; delete headers['content-length'];
        await route.fulfill({ status: response.status(), headers, body }); await response.dispose();
      } catch (error) {
        warnings.add(['UNSAFE_URL','REQUEST_METHOD_BLOCKED','REQUEST_LIMIT','ROBOTS_BLOCKED','REDIRECT_REQUIRES_REVIEW','BLOCKED','RESPONSE_TOO_LARGE'].includes(error.message) ? error.message : 'BROWSER_NETWORK_ERROR');
        await route.abort().catch(() => {});
      }
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
          const body = await frame.content(), key = frame.url() + body;
          if (seen.has(key)) continue;
          if (Buffer.byteLength(snapshots.map(item => item.body).join('') + body) > 1500000) { warnings.add('DOM_LIMIT'); continue; }
          seen.add(key); snapshots.push({ url: frame.url(), body, contentType: 'text/html' });
        } catch { warnings.add('FRAME_INCOMPLETE'); }
      }
      if (page.frames().length > 5) warnings.add('FRAME_LIMIT');
    };
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: Math.min(12000, timeout) });
    await settle(); await snapshot();
    const title = (await page.title()).toLowerCase();
    if (/captcha|just a moment|access denied|verify.*human/.test(title)) throw new Error('BLOCKED');
    for (let step = 0; step < 2 && Date.now() < deadline - 1000; step++) {
      await page.evaluate(() => window.scrollBy(0, Math.min(document.body.scrollHeight, 1200)));
      await settle(); await snapshot();
    }
    const navigation = page.locator('button, a[rel="next"], [role="button"]').filter({ hasText: /^(mehr (veranstaltungen|events|laden)|load more(?: events)?|next(?: month| page)?|nächste[rns]? (monat|seite)|weiter)$/i });
    for (let step = 0; step < 2 && Date.now() < deadline - 2500; step++) {
      const next = navigation.first();
      if (!await next.count() || !await next.isVisible() || !await next.isEnabled()) break;
      await next.click({ timeout: 1500 }); await settle(); await snapshot();
      if (step === 1 && await next.isVisible().catch(() => false) && await next.isEnabled().catch(() => false)) warnings.add('PAGINATION_LIMIT');
    }
    if (Date.now() >= deadline) warnings.add('BROWSER_TIMEOUT');
    return { status: warnings.size ? 'BROWSER_PARTIAL' : 'RENDERED', pages: snapshots, warnings: [...warnings], complete: false };
  } catch (error) {
    const status = ['BLOCKED', 'BROWSER_TIMEOUT'].includes(error.message) ? error.message : error.name === 'TimeoutError' ? 'BROWSER_TIMEOUT' : 'BROWSER_FAILED';
    return { status, pages: snapshots, warnings: [...warnings, status], complete: false };
  } finally { if (browser) await browser.close(); }
}
