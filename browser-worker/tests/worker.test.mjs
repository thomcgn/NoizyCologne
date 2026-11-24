import { test } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { once, EventEmitter } from 'node:events';
import { createWorker, runChild } from '../server.mjs';
import { createGateway, readPublic } from '../gateway.mjs';
import { proxySecret } from '../security.mjs';
const token = 'test-token-for-isolated-fixtures-only-12345';
const headers = { Authorization:`Bearer ${token}`, 'Content-Type':'application/json' };
async function listen(server, work) {
  server.listen(0,'127.0.0.1'); await once(server,'listening');
  try { await work(`http://127.0.0.1:${server.address().port}`); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
test('worker authenticates, validates URL, serializes jobs and returns failures honestly', async () => {
  let complete, started, jobs=0; const running=new Promise(resolve => {started=resolve;});
  await listen(createWorker(token,async () => {jobs++;started();return new Promise(resolve => {complete=resolve;});}),async base => {
    assert.equal((await fetch(`${base}/render`,{method:'POST',body:'{}'})).status,403);
    assert.equal((await fetch(`${base}/render`,{method:'POST',headers,body:JSON.stringify({url:'file:///etc/passwd'})})).status,400);
    const first=fetch(`${base}/render`,{method:'POST',headers,body:JSON.stringify({url:'https://club.example/'})}); await running;
    const busy=await fetch(`${base}/render`,{method:'POST',headers,body:JSON.stringify({url:'https://club.example/'})});
    assert.equal(busy.status,429); assert.equal((await busy.json()).status,'BROWSER_BUSY');
    complete({status:'BROWSER_TIMEOUT',pages:[],warnings:['BROWSER_TIMEOUT'],complete:false});
    assert.equal((await (await first).json()).status,'BROWSER_TIMEOUT');assert.equal(jobs,1);
  });
});
test('gateway robots is authenticated and fails closed on redirects',async () => {
  await listen(createGateway(token,{read:async () => ({status:302,body:''})}),async base => {
    const options={method:'POST',headers:{...headers,Authorization:`Bearer ${proxySecret(token)}`},body:JSON.stringify({url:'https://club.example/private'})};
    assert.equal((await fetch(`${base}/robots`,{...options,headers})).status,403);
    assert.equal((await fetch(`${base}/robots`,options)).status,403);
  });
});
test('CONNECT rejects private DNS before creating any upstream socket',async () => {
  let connected=false;
  await listen(createGateway(token,{lookup:async () => [{address:'127.0.0.1',family:4}],connect:() => {connected=true;throw new Error();}}),async base => {
    const client=net.createConnection({host:'127.0.0.1',port:Number(new URL(base).port)});await once(client,'connect');
    client.write(`CONNECT club.example:443 HTTP/1.1\r\nHost: club.example:443\r\nProxy-Authorization: Basic ${Buffer.from(`discovery:${proxySecret(token)}`).toString('base64')}\r\n\r\n`);
    const [data]=await once(client,'data');assert.match(data.toString(),/403/);client.destroy();
  });
  assert.equal(connected,false);
});
test('gateway keeps 32 tunnels for relevant egress and drops telemetry before reserving one',async () => {
  const {PassThrough}=await import('node:stream');
  const connected=[], warnings=[], sockets=[], originalWarn=console.warn;
  console.warn=(...parts)=>warnings.push(parts.join(' '));
  const proxyAuth=`Basic ${Buffer.from(`discovery:${proxySecret(token)}`).toString('base64')}`;
  try {
    await listen(createGateway(token,{lookup:async () => [{address:'93.184.216.34',family:4}],
      connect:options => {connected.push(options.host);const upstream=new PassThrough();queueMicrotask(()=>upstream.emit('connect'));return upstream;}}),async base => {
      const port=Number(new URL(base).port);
      const connectTo=host=>new Promise(resolve=>{
        const client=net.createConnection({host:'127.0.0.1',port});sockets.push(client);
        client.once('connect',()=>client.write(`CONNECT ${host}:443 HTTP/1.1\r\nHost: ${host}:443\r\nProxy-Authorization: ${proxyAuth}\r\n\r\n`));
        client.on('data',chunk=>{const line=chunk.toString().split('\r\n')[0];if(/HTTP\/1\.1 /.test(line))resolve(line);});
        client.on('error',()=>resolve('ERROR'));
      });
      const telemetry=await Promise.all(Array.from({length:6},()=>connectTo('frog.wix.com')));
      assert.ok(telemetry.every(line=>/403/.test(line)),JSON.stringify(telemetry));
      assert.equal(connected.length,0);
      const assets=await Promise.all(Array.from({length:40},()=>connectTo('static.wixstatic.com')));
      assert.equal(assets.filter(line=>/200 Connection Established/.test(line)).length,32);
      assert.equal(assets.filter(line=>/403/.test(line)).length,8);
      assert.equal(connected.length,32);
      assert.ok(warnings.some(entry=>entry.includes('BROWSER_PROXY_EGRESS_DEPRIORITIZED')));
      assert.ok(warnings.some(entry=>entry.includes('BROWSER_PROXY_CONNECTION_LIMIT')));
      for(const socket of sockets)socket.destroy();
    });
  } finally {for(const socket of sockets)socket.destroy();console.warn=originalWarn;}
});
test('robots socket uses the checked IP rather than repeating DNS resolution',async () => {
  const {PassThrough}=await import('node:stream');const address={address:'93.184.216.34',family:4};let pinned;
  const result=await readPublic('https://club.example/robots.txt',{lookup:async () => [address],get:(_url,options,callback) => {
    options.lookup('club.example',{all:true},(_error,values) => {pinned=values;});
    const request=new PassThrough();request.destroy=() => {};
    queueMicrotask(() => {const response=new PassThrough();response.statusCode=200;callback(response);response.end('User-agent: *\nDisallow: /private');request.emit('close');});return request;
  }});
  assert.deepEqual(pinned,[address]);assert.equal(result.status,200);
});

test('watchdog and crashed children require container retirement; normal replies do not',{timeout:1000},async () => {
  const normal={status:'RENDERED',pages:[],warnings:[],complete:false};
  const forkProcess=mode=>()=>{
    const child=new EventEmitter();
    child.send=()=>{
      if(mode==='timeout')return;
      else queueMicrotask(()=>{if(mode==='normal')child.emit('message',normal);child.emit('exit',mode==='normal'?0:1);});
    };
    return child;
  };
  const success=await runChild({url:'https://fixture.example/'},{forkProcess:forkProcess('normal')});
  assert.equal(success.restartWorker,false);assert.equal(success.status,'RENDERED');
  const crash=await runChild({url:'https://fixture.example/'},{forkProcess:forkProcess('crash')});
  assert.equal(crash.restartWorker,true);assert.equal(crash.status,'BROWSER_UNAVAILABLE');
  const timeout=await runChild({url:'https://fixture.example/'},{forkProcess:forkProcess('timeout'),timeout:1});
  assert.equal(timeout.restartWorker,true);assert.equal(timeout.status,'BROWSER_TIMEOUT');
});
test('worker flushes fatal result and retires without exposing its internal control marker',async () => {
  let retired=0;
  await listen(createWorker(token,async()=>({status:'BROWSER_TIMEOUT',pages:[],warnings:['BROWSER_TIMEOUT'],complete:false,restartWorker:true}),()=>{retired++;}),async base=>{
    const response=await fetch(`${base}/render`,{method:'POST',headers,body:JSON.stringify({url:'https://fixture.example/'})});
    const result=await response.json();assert.equal(result.status,'BROWSER_TIMEOUT');assert.equal(result.restartWorker,undefined);
    assert.equal((await fetch(`${base}/render`,{method:'POST',headers,body:JSON.stringify({url:'https://fixture.example/'})})).status,429);
    assert.equal(retired,1);
  });
});


test('gateway readiness fails when worker health fails and does not claim a render success',async()=>{
  const {createServer}=await import('node:http');
  let healthy=true;
  const worker=createServer((_request,response)=>{response.writeHead(healthy?200:503,{'Content-Type':'application/json'});response.end(JSON.stringify({ok:healthy}));});
  await listen(worker,async workerBase=>{
    const gateway=createGateway(token,{workerHost:'127.0.0.1',workerPort:Number(new URL(workerBase).port)});
    await listen(gateway,async base=>{
      assert.equal((await fetch(`${base}/ready`)).status,200);
      healthy=false;
      const failed=await fetch(`${base}/ready`);
      assert.equal(failed.status,503);assert.equal((await failed.json()).status,'BROWSER_WORKER_UNAVAILABLE');
      assert.equal((await fetch(`${base}/health`)).status,200);
    });
  });
});

test('production smoke exercises only read-only preview and rejects infrastructure failures without hiding partial lists',async()=>{
  const {spawnSync}=await import('node:child_process');
  const smoke=new URL('../smoke.mjs',import.meta.url).href;
  for(const status of ['BROWSER_PARTIAL','BROWSER_DISABLED','BROWSER_AUTH_FAILED','BROWSER_WORKER_UNAVAILABLE']){
    const report={checkedAt:'2026-11-09T12:00:00Z',eventCount:0,incomplete:true,sources:[{url:'https://club.example/about',fetchMethod:'BROWSER',diagnostics:[status],extraction:{status,events:[],complete:false}}]};
    const script=`const report=${JSON.stringify(report)};globalThis.fetch=async(url,options)=>{if(url.endsWith('/ready'))return {ok:true};if(!url.endsWith('/onboard')||options.method!=='POST')throw Error('Unexpected mutation');return {ok:true,json:async()=>report};};await import(${JSON.stringify(smoke)});`;
    const result=spawnSync(process.execPath,['--input-type=module','-e',script],{encoding:'utf8'});
    assert.equal(result.status,status==='BROWSER_PARTIAL'?0:1,result.stderr);
    assert.match(result.stdout,/Liste nicht vollständig/);
    assert.match(result.stdout,/"event_count": 0/);
  }
});

test('production smoke obtains internal API key from stdin without exposing it',async()=>{
  const {spawnSync}=await import('node:child_process');
  const secret='isolated-api-key-fixture-at-least-32-characters';
  const smoke=new URL('../smoke.mjs',import.meta.url).href;
  const script=`process.argv.push('--key-stdin');globalThis.fetch=async(url,options)=>{if(url.endsWith('/ready'))return {ok:true};if(options.headers['X-Noizy-Api-Key']!==${JSON.stringify(secret)}||options.headers.Origin!=='https://tsa.noizycologne.de')throw Error('Wrong internal identity');return {ok:true,json:async()=>({eventCount:0,incomplete:true,sources:[{url:'https://fixture.example/',fetchMethod:'BROWSER',diagnostics:[],extraction:{status:'BROWSER_PARTIAL',events:[],complete:false}}]})};};await import(${JSON.stringify(smoke)});`;
  const result=spawnSync(process.execPath,['--input-type=module','-e',script],{encoding:'utf8',input:secret+'\n'});
  assert.equal(result.status,0,result.stderr);assert.ok(!result.stdout.includes(secret));
});
