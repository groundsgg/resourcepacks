import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';

import { fakeHttp } from './fake-http.mjs';
import { verifyCdn } from '../src/verify-cdn.mjs';

const bytes = Buffer.from('zip payload');
const sha1 = createHash('sha1').update(bytes).digest('hex');
const sha256 = createHash('sha256').update(bytes).digest('hex');

test('verifyCdn accepts complete bytes and semantic cache directives in any order', async t => {
  const http = await fakeHttp((request, response) => {
    response.writeHead(200, {
      'Content-Type': 'application/zip',
      'Cache-Control': 'immutable, public, max-age=31536000',
    });
    response.write(bytes.subarray(0, 3));
    response.end(bytes.subarray(3));
  });
  t.after(http.close);

  await verifyCdn({ packs: [{ url: 'https://cdn.grounds.gg/pack.zip', sha1, sha256, size: bytes.length }] }, {baseUrl:http.url});
});

test('verifyCdn rejects duplicate and conflicting cache directives', async t => {
  for (const cacheControl of [
    'public, immutable, max-age=31536000, public',
    'public, immutable, max-age=31536000, no-store',
    'public, immutable, max-age=31536000, private',
    'public, immutable, max-age=31536000, s-maxage=0',
  ]) {
    const http = await fakeHttp((request, response) => {
      response.writeHead(200, {
        'Content-Type': 'application/zip',
        'Cache-Control': cacheControl,
      });
      response.end(bytes);
    });
    t.after(http.close);

    await assert.rejects(
      () => verifyCdn({ packs: [{ url: 'https://cdn.grounds.gg/pack.zip', sha1, sha256, size: bytes.length }] }, {baseUrl:http.url}),
      /Cache-Control mismatch/,
    );
  }
});

test('verifyCdn rejects a redirect to another host without downloading it', async t => {
  const http = await fakeHttp((request, response) => {
    response.writeHead(302, { Location: 'http://example.invalid/pack.zip' });
    response.end();
  });
  t.after(http.close);

  await assert.rejects(
    () => verifyCdn({ packs: [{ url: 'https://cdn.grounds.gg/pack.zip', sha1, sha256, size: bytes.length }] }, {baseUrl:http.url}),
    /redirected to another host/,
  );
});

test('verifyCdn rejects wrong MIME and incomplete bodies', async t => {
  const wrong = await fakeHttp((request, response) => response.writeHead(200, {'Content-Type':'text/plain','Cache-Control':'public, immutable, max-age=31536000'}).end(bytes));
  t.after(wrong.close);
  await assert.rejects(() => verifyCdn({packs:[{url:'https://cdn.grounds.gg/x',sha1,sha256,size:bytes.length}]},{baseUrl:wrong.url}), /Content-Type mismatch/);
  const cut = await fakeHttp((request, response) => response.writeHead(200, {'Content-Type':'application/zip','Cache-Control':'public, immutable, max-age=31536000'}).end(bytes.subarray(0,2)));
  t.after(cut.close);
  await assert.rejects(() => verifyCdn({packs:[{url:'https://cdn.grounds.gg/x',sha1,sha256,size:bytes.length}]},{baseUrl:cut.url}), /bytes mismatch/);
});

test('verifyCdn rejects hostile advertised sizes, oversized bodies, and network failures', async t => {
  const advertised=await fakeHttp((request,response)=>response.writeHead(200,{'Content-Type':'application/zip','Cache-Control':'public, immutable, max-age=31536000','Content-Length':String(bytes.length+1)}).end(bytes));
  t.after(advertised.close);
  await assert.rejects(()=>verifyCdn({packs:[{url:'https://cdn.grounds.gg/x',sha1,sha256,size:bytes.length}]},{baseUrl:advertised.url}),/Content-Length mismatch/);
  const oversized=await fakeHttp((request,response)=>response.writeHead(200,{'Content-Type':'application/zip','Cache-Control':'public, immutable, max-age=31536000'}).end(Buffer.concat([bytes,Buffer.from('x')])));
  t.after(oversized.close);
  await assert.rejects(()=>verifyCdn({packs:[{url:'https://cdn.grounds.gg/x',sha1,sha256,size:bytes.length}]},{baseUrl:oversized.url}),/safe limit/);
  await assert.rejects(()=>verifyCdn({packs:[{url:'https://cdn.grounds.gg/x',sha1,sha256,size:bytes.length}]},{baseUrl:'http://127.0.0.1:1'}),/request failed/);
});

test('verifyCdn times out both pending headers and a stalled response body',async()=>{const manifest={packs:[{url:'https://cdn.grounds.gg/x',sha1,sha256,size:bytes.length}]};const pending=verifyCdn(manifest,{baseUrl:'https://cdn.grounds.gg',fetchImpl:()=>new Promise(()=>{}),timeoutMs:20});assert.equal(await Promise.race([assert.rejects(()=>pending,/CDN request timed out/).then(()=> 'timeout'),new Promise(resolve=>setTimeout(()=>resolve('hung'),200))]),'timeout');const stalled=verifyCdn(manifest,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>new Response(new ReadableStream({start(){}}),{status:200,headers:{'Content-Type':'application/zip','Cache-Control':'public, immutable, max-age=31536000'}}),timeoutMs:20});assert.equal(await Promise.race([assert.rejects(()=>stalled,/CDN request timed out/).then(()=> 'timeout'),new Promise(resolve=>setTimeout(()=>resolve('hung'),200))]),'timeout');});
