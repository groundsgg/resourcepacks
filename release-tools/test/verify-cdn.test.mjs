import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import test from 'node:test';

import { fakeHttp } from './fake-http.mjs';
import { canonicalJson, createReleaseFixture } from './fixtures.mjs';
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

test('verifyCdn follows a manifest-bound historical build pack key',async t=>{
  const current=await createReleaseFixture({type:'build',commit:'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'});
  const historical=await createReleaseFixture({type:'build',commit:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'});
  current.manifest.packs[1].url=historical.manifest.packs[1].url;
  const manifestBytes=Buffer.from(canonicalJson(current.manifest));current.files.set('manifest.json',manifestBytes);
  await writeFile(`${current.root}/manifest.json`,manifestBytes);
  const release=await loadRelease({manifestFile:`${current.root}/manifest.json`,releaseDirectory:current.root});t.after(()=>release.close());
  const expected=release.artifacts.map(artifact=>`/${artifact.key}`);const seen=[];
  const http=await fakeHttp((request,response)=>{seen.push(request.url);const artifact=release.artifacts.find(item=>request.url===`/${item.key}`);assert.ok(artifact);response.writeHead(200,{'Content-Type':artifact.contentType,'Cache-Control':'public, immutable, max-age=31536000'}).end(current.files.get(artifact.name));});t.after(http.close);
  assert.deepEqual(await verifyCdn(release,{baseUrl:http.url}),{verified:4});
  assert.deepEqual(seen,expected);
  assert.ok(seen.includes(`/resourcepacks/packsets/grounds-global/builds/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/${historical.manifest.packs[1].url.split('/').at(-1)}`));
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

test('verifyCdn rejects caller-mutated artifact contracts before fetching',async()=>{
  const {release}=await fixture();release.artifacts[0].key='resourcepacks/attacker';let calls=0;
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>{calls+=1;return new Response();}}),/release artifact contract mismatch/);
  assert.equal(calls,0);await release.close();
});

test('verifyCdn preserves hostile response rejection paths',async()=>{
  const {release}=await fixture();const artifact=release.artifacts[0];const response=overrides=>new Response(overrides.body??Buffer.alloc(artifact.size),{status:overrides.status??200,headers:{'Content-Type':artifact.contentType,'Cache-Control':'public, immutable, max-age=31536000',...overrides.headers}});
  for(const headers of [
    {'Cache-Control':'public, immutable, max-age=31536000, public'},
    {'Cache-Control':'public, immutable, max-age=31536000, no-store'},
    {'Cache-Control':'public, immutable, max-age=31536000, private'},
    {'Cache-Control':'public, immutable, max-age=31536000, s-maxage=0'},
    {'Content-Length':String(artifact.size+1)},
  ]) await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>response({headers})}),/Cache-Control|Content-Length mismatch/);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>response({body:Buffer.concat([Buffer.alloc(artifact.size),Buffer.from('x')])})}),/safe limit/);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>response({body:Buffer.alloc(1)})}),/bytes mismatch/);
  for(const redirect of [new Response('',{status:302}),new Response('',{status:302,headers:{Location:'https://attacker.example/x'}})]) await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>redirect}),/redirect missing|another host/);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>new Response('',{status:302,headers:{Location:'/again'}})}),/redirect limit/);
  await assert.rejects(()=>verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async()=>{throw new Error('offline');}}),/request failed/);
  await release.close();
});

test('verifyCdn keeps captured URLs when the public release mutates during the first fetch',async()=>{
  const {value,release}=await fixture();const expected=release.artifacts.map(artifact=>`/${artifact.key}`);const responses=new Map(release.artifacts.map(artifact=>[`/${artifact.key}`,{bytes:value.files.get(artifact.name),contentType:artifact.contentType}]));const seen=[];let calls=0;
  await verifyCdn(release,{baseUrl:'https://cdn.grounds.gg',fetchImpl:async url=>{const path=new URL(url).pathname;seen.push(path);const artifact=responses.get(path);if(calls++===0){release.layout.root='resourcepacks/attacker';release.artifacts[1].name='attacker.zip';release.manifest.publication.id='v9.9.9';}return new Response(artifact.bytes,{status:200,headers:{'Content-Type':artifact.contentType,'Cache-Control':'public, immutable, max-age=31536000'}});}});
  assert.deepEqual(seen,expected);await release.close();
});
