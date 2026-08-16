import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import test from 'node:test';

import { S3ServiceException } from '@aws-sdk/client-s3';

import { fakeHttp } from './fake-http.mjs';
import { createOrCompare } from '../src/r2-create-or-compare.mjs';
import { r2ReleaseCreateOrCompare } from '../src/r2-create-or-compare.mjs';
import { createReleaseFixture } from './fixtures.mjs';
import { loadRelease } from '../src/manifest.mjs';

const expected=bytes=>({size:bytes.length,sha1:createHash('sha1').update(bytes).digest('hex'),sha256:createHash('sha256').update(bytes).digest('hex')});

test('createOrCompare makes an immutable conditional upload', async t => {
  const body = Buffer.from('zip bytes');
  const directory = await mkdtemp(join(tmpdir(), 'r2-test-'));
  const file = join(directory, 'pack.zip');
  await writeFile(file, body);
  const http = await fakeHttp(async (request, response) => {
    assert.equal(request.method, 'PUT');
    assert.equal(request.headers['if-none-match'], '*');
    assert.equal(request.headers['content-type'], 'application/zip');
    assert.equal(request.headers['cache-control'], 'public, max-age=31536000, immutable');
    assert.match(request.headers.authorization, /^AWS4-HMAC-SHA256 Credential=test\//);
    assert.match(request.headers['x-amz-date'], /^\d{8}T\d{6}Z$/);
    assert.ok(request.headers['x-amz-content-sha256']);
    for await (const _chunk of request) { /* consume */ }
    response.writeHead(200).end();
  });
  t.after(http.close);

  await createOrCompare({ endpoint: http.url, bucket: 'packs', key: 'resourcepacks/content/a.zip', file, accessKey: 'test', secretKey: 'test', expected:expected(body) });
});

test('createOrCompare accepts a 412 only when the complete existing object is equal', async t => {
  const body = Buffer.from('zip bytes');
  const directory = await mkdtemp(join(tmpdir(), 'r2-test-'));
  const file = join(directory, 'pack.zip');
  await writeFile(file, body);
  const http = await fakeHttp((request, response) => {
    if (request.method === 'PUT') {
      assert.match(request.headers.authorization, /^AWS4-HMAC-SHA256 Credential=test\//);
      assert.ok(request.headers['x-amz-date']);
      assert.ok(request.headers['x-amz-content-sha256']);
    }
    if (request.method === 'PUT') return response.writeHead(412).end();
    response.writeHead(200, { 'Content-Type': 'application/zip', 'Cache-Control':'public, max-age=31536000, immutable' });
    response.write(body.subarray(0, 2));
    response.end(body.subarray(2));
  });
  t.after(http.close);

  await createOrCompare({ endpoint: http.url, bucket: 'packs', key: 'resourcepacks/content/a.zip', file, accessKey: 'test', secretKey: 'test', expected:expected(body) });
});

test('createOrCompare rejects a conflicting or oversized existing object', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'r2-test-')); const file = join(directory, 'pack.zip'); await writeFile(file, 'x');
  const http = await fakeHttp((request, response) => request.method === 'PUT' ? response.writeHead(412).end() : response.writeHead(200).end('different'));
  t.after(http.close);
  await assert.rejects(() => createOrCompare({endpoint:http.url,bucket:'packs',key:'x',file,accessKey:'test',secretKey:'test',expected:expected(Buffer.from('x'))}), /differs/);
});

test('createOrCompare rejects equal bytes with non-immutable existing metadata', async t => {
  const directory=await mkdtemp(join(tmpdir(),'r2-test-'));const file=join(directory,'pack.zip');await writeFile(file,'same');
  const http=await fakeHttp((request,response)=>request.method==='PUT'?response.writeHead(412).end():response.writeHead(200,{'Content-Type':'application/zip','Cache-Control':'max-age=0'}).end('same'));
  t.after(http.close);
  await assert.rejects(()=>createOrCompare({endpoint:http.url,bucket:'packs',key:'x',file,accessKey:'test',secretKey:'test',expected:expected(Buffer.from('same'))}),/metadata differs/);
});

test('createOrCompare converts endpoint failures to credential-free diagnostics', async () => {
  const directory=await mkdtemp(join(tmpdir(),'r2-test-'));const file=join(directory,'pack.zip');await writeFile(file,'same');
  await assert.rejects(()=>createOrCompare({endpoint:'http://127.0.0.1:1',bucket:'packs',key:'x',file,accessKey:'ACCESS_MARKER',secretKey:'SECRET_MARKER',expected:expected(Buffer.from('same'))}),error=>{
    assert.match(error.message,/R2 upload failed/);
    assert.doesNotMatch(error.message,/ACCESS_MARKER|SECRET_MARKER/);
    return true;
  });
});

test('createOrCompare verifies expected bytes before a successful immutable PUT',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'r2-test-'));const file=join(directory,'pack.zip');await writeFile(file,'corrupt');let sends=0;
  const client={async send(){sends+=1;return {};}};
  await assert.rejects(()=>createOrCompare({endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',key:'x',file,accessKey:'test',secretKey:'test',expected:{sha1:'0'.repeat(40),sha256:'0'.repeat(64),size:7},client}),/does not match manifest/);
  assert.equal(sends,0);
});

test('createOrCompare uploads a verified private snapshot despite in-place source mutation',async()=>{
  const original=Buffer.from('original');const directory=await mkdtemp(join(tmpdir(),'r2-test-'));const file=join(directory,'pack.zip');await writeFile(file,original);let uploaded;
  const client={async send(command){await writeFile(file,'modified');const chunks=[];for await(const chunk of command.input.Body)chunks.push(Buffer.from(chunk));uploaded=Buffer.concat(chunks);return {};}};
  assert.deepEqual(await createOrCompare({endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',key:'x',file,accessKey:'test',secretKey:'test',expected:expected(original),client}),{created:true});
  assert.deepEqual(uploaded,original);
});
test('createOrCompare times out a stalled AWS request with stable diagnostics',async()=>{const body=Buffer.from('same');const directory=await mkdtemp(join(tmpdir(),'r2-test-'));const file=join(directory,'pack.zip');await writeFile(file,body);const operation=createOrCompare({endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',key:'x',file,accessKey:'ACCESS_MARKER',secretKey:'SECRET_MARKER',expected:expected(body),client:{send:()=>new Promise(()=>{})},timeoutMs:20});assert.equal(await Promise.race([assert.rejects(()=>operation,/R2 request timed out/).then(()=> 'timeout'),new Promise(resolve=>setTimeout(()=>resolve('hung'),200))]),'timeout');});

test('createOrCompare times out a stalled R2 comparison body',async()=>{
  const body=Buffer.from('same');const directory=await mkdtemp(join(tmpdir(),'r2-test-'));const file=join(directory,'pack.zip');await writeFile(file,body);const stalled=new PassThrough();let calls=0;
  const client={async send(){calls+=1;if(calls===1)throw new S3ServiceException({name:'PreconditionFailed',$fault:'client',$metadata:{httpStatusCode:412}});return{Body:stalled,ContentType:'application/zip',CacheControl:'public, max-age=31536000, immutable'};}};
  const operation=createOrCompare({endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',key:'x',file,accessKey:'test',secretKey:'test',expected:expected(body),client,timeoutMs:20});
  assert.equal(await Promise.race([assert.rejects(()=>operation,/R2 request timed out/).then(()=> 'timeout'),new Promise(resolve=>setTimeout(()=>resolve('hung'),200))]),'timeout');
  assert.equal(stalled.destroyed,true);
});

test('R2 release publication rejects caller-mutated artifact contracts before any request',async()=>{
  for(const mutate of [
    value=>{value.artifacts[0].key='resourcepacks/attacker';},
    value=>{value.artifacts[0].name='other.zip';},
    value=>{value.artifacts[0].role='catalog';},
    value=>{value.artifacts[0].contentType='text/plain';},
    value=>{value.artifacts[0].path='/tmp/attacker';},
    value=>{value.artifacts=[value.artifacts[0],value.artifacts[0],...value.artifacts.slice(2)];},
    value=>{value.artifacts.reverse();},
  ]){
    const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:join(fixture.root,'manifest.json'),releaseDirectory:fixture.root});mutate(release);let sends=0;
    await assert.rejects(()=>r2ReleaseCreateOrCompare({release,endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',accessKey:'test',secretKey:'test',client:{async send(){sends+=1;}}}),/release artifact contract mismatch/);
    assert.equal(sends,0);
    await release.close();
  }
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:join(fixture.root,'manifest.json'),releaseDirectory:fixture.root});let sends=0;
  await assert.rejects(()=>r2ReleaseCreateOrCompare({release:{...release},endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',accessKey:'test',secretKey:'test',client:{async send(){sends+=1;}}}),/release artifact contract mismatch/);
  assert.equal(sends,0);await release.close();
});

test('R2 keeps the captured immutable keys when the public release mutates during publication',async()=>{
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:join(fixture.root,'manifest.json'),releaseDirectory:fixture.root});const expectedKeys=release.artifacts.map(artifact=>artifact.key);const keys=[];let calls=0;
  const client={async send(command){keys.push(command.input.Key);if(calls++===0){release.manifest.publication.id='v9.9.9';release.manifest.provenance.commit='f'.repeat(40);release.artifacts[1].key='resourcepacks/attacker';release.manifestArtifact.snapshot={bytes:Buffer.from('attacker')};}return {};}};
  await assert.rejects(()=>r2ReleaseCreateOrCompare({release,endpoint:'https://example.r2.cloudflarestorage.com',bucket:'packs',accessKey:'test',secretKey:'test',client}),/R2 upload failed|artifact contract mismatch/);
  assert.ok(keys.every(key=>expectedKeys.includes(key)));
  await release.close();
});
