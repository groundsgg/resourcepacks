import assert from 'node:assert/strict';
import test from 'node:test';

import { fakeHttp } from './fake-http.mjs';
import { createReleaseFixture } from './fixtures.mjs';
import { loadRelease } from '../src/manifest.mjs';
import { verifyCdn } from '../src/verify-cdn.mjs';

async function fixture(type='release') {
  const value=await createReleaseFixture({type});
  const release=await loadRelease({manifestFile:`${value.root}/manifest.json`,releaseDirectory:value.root});
  return {value,release};
}

test('verifyCdn derives and verifies exactly four schema-v2 release URLs',async t=>{
  const {value,release}=await fixture();t.after(()=>release.close());const seen=[];
  const http=await fakeHttp((request,response)=>{seen.push(request.url);const artifact=release.artifacts.find(item=>request.url===`/${item.key}`);assert.ok(artifact);response.writeHead(200,{'Content-Type':artifact.contentType,'Cache-Control':'immutable, public, max-age=31536000'}).end(value.files.get(artifact.name));});t.after(http.close);
  assert.deepEqual(await verifyCdn(release,{baseUrl:http.url}),{verified:4});
  assert.deepEqual(seen.sort(),release.artifacts.map(item=>`/${item.key}`).sort());
});

test('verifyCdn derives the build URL set from the full commit root',async t=>{
  const {value,release}=await fixture('build');t.after(()=>release.close());const seen=[];
  const http=await fakeHttp((request,response)=>{seen.push(request.url);const artifact=release.artifacts.find(item=>request.url===`/${item.key}`);response.writeHead(200,{'Content-Type':artifact.contentType,'Cache-Control':'public, immutable, max-age=31536000'}).end(value.files.get(artifact.name));});t.after(http.close);
  await verifyCdn(release,{baseUrl:http.url});
  assert.ok(seen.every(path=>path.includes(`/builds/${release.manifest.provenance.commit}/`)));
});

test('verifyCdn rejects metadata and byte mutations',async t=>{
  const {release}=await fixture();t.after(()=>release.close());
  const wrong=await fakeHttp((request,response)=>response.writeHead(200,{'Content-Type':'text/plain','Cache-Control':'public, immutable, max-age=31536000'}).end('x'));t.after(wrong.close);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:wrong.url}),/Content-Type mismatch/);
  const changed=await fakeHttp((request,response)=>{const artifact=release.artifacts.find(item=>request.url===`/${item.key}`);response.writeHead(200,{'Content-Type':artifact.contentType,'Cache-Control':'public, immutable, max-age=31536000'}).end(Buffer.from('changed'));});t.after(changed.close);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:changed.url}),/bytes mismatch/);
});

test('verifyCdn times out pending headers and a stalled response body',async()=>{
  const {release}=await fixture();
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:()=>new Promise(()=>{}),timeoutMs:20}),/CDN request timed out/);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>new Response(new ReadableStream({start(){}}),{status:200,headers:{'Content-Type':'application/zip','Cache-Control':'public, immutable, max-age=31536000'}}),timeoutMs:20}),/CDN request timed out/);
  await release.close();
});
