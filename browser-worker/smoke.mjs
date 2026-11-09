// Run inside the private gateway container; read-only onboarding preview, never /scan or /sources.
const ready=await fetch('http://127.0.0.1:3128/ready',{signal:AbortSignal.timeout(4000)});
if(!ready.ok)throw new Error('BROWSER_WORKER_UNAVAILABLE: gateway readiness failed');
const response=await fetch('http://api:8080/api/v1/discovery/onboard',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({url:'https://www.mtc-cologne.de/'}),signal:AbortSignal.timeout(90000)});
if(!response.ok)throw new Error(`BROWSER_API_SMOKE_FAILED: HTTP ${response.status}`);
const report=await response.json();
const failures=new Set(['BROWSER_DISABLED','BROWSER_UNAVAILABLE','BROWSER_FAILED','BROWSER_TIMEOUT','BROWSER_SANDBOX_FAILED','BROWSER_AUTH_FAILED','BROWSER_WORKER_UNAVAILABLE']);
const sources=report.sources.map(source=>({url:source.url,fetch_method:source.fetchMethod,status:source.extraction.status,
  complete:source.extraction.complete,event_count:source.extraction.events.length,diagnostics:source.diagnostics}));
console.log(JSON.stringify({checked_at:report.checkedAt,event_count:report.eventCount,
  list:report.incomplete?'Liste nicht vollständig':'Vollständig',sources},null,2));
if(sources.some(source=>failures.has(source.status)||source.diagnostics.some(code=>failures.has(code))))throw new Error('BROWSER_API_SMOKE_FAILED: browser infrastructure is unavailable');
if(!sources.some(source=>source.fetch_method==='BROWSER') && report.eventCount===0)throw new Error('BROWSER_FALLBACK_NOT_REACHED: neither dated events nor browser evidence');
// BROWSER_PARTIAL / BLOCKED is evidence of a website limitation, not an extraction success.
