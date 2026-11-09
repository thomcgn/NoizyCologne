import { chromium } from 'playwright';

// Executed once at container startup, before readiness; no website or database access.
export async function verifySandbox() {
  const browser = await chromium.launch({headless:true,chromiumSandbox:true,timeout:10000,
    env:{PATH:process.env.PATH,HOME:'/tmp',LANG:'de_DE.UTF-8'}});
  try { const page=await browser.newPage(); await page.goto('about:blank'); }
  finally { await browser.close(); }
}
