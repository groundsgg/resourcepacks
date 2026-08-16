import assert from 'node:assert/strict';
import test from 'node:test';

import { advanceChannel } from '../src/r2-channel-advance.mjs';
import { encodeChannel } from '../src/channel.mjs';
import { loadRelease } from '../src/manifest.mjs';
import { createReleaseFixture } from './fixtures.mjs';
import { PassThrough } from 'node:stream';

test('advanceChannel creates an absent Stable pointer with the exact conditional metadata and Kotlin bytes', async () => {
  const fixture = await createReleaseFixture();
  const release = await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  const sent = [];
  const s3Client = { async send(command) {
    sent.push(command.input);
    if (command.constructor.name === 'GetObjectCommand') {
      if (command.input.Key.endsWith('/manifest.json')) return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};
      const error = new Error('missing'); error.name = 'NoSuchKey'; error.$metadata = {httpStatusCode:404}; throw error;
    }
    return {};
  }};
  try {
    assert.deepEqual(await advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'access',secretKey:'secret',sequence:7,s3Client}), {decision:'created',channel:'stable',sequence:7});
    assert.equal(sent.length, 3);
    const put = sent[2];
    assert.equal(put.Key, 'resourcepacks/packsets/grounds-global/channels/stable.json');
    assert.equal(put.IfNoneMatch, '*');
    assert.equal(put.ContentType, 'application/json; charset=utf-8');
    assert.equal(put.CacheControl, 'public, max-age=30, must-revalidate');
    assert.equal(Buffer.from(put.Body).toString('utf8'), `{
  "channel": "stable",
  "manifest": {
    "sha256": "${release.manifestArtifact.sha256}",
    "size": ${release.manifestArtifact.size},
    "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.0/manifest.json"
  },
  "packSet": "grounds-global",
  "schemaVersion": 2,
  "sequence": 7,
  "target": {
    "id": "v0.1.0",
    "type": "release"
  }
}
`);
  } finally { await release.close(); }
});

test('advanceChannel refuses an absent-pointer create race without reporting channel advancement', async () => {
  const fixture = await createReleaseFixture();
  const release = await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  const sent = [];
  const s3Client = { async send(command) {
    sent.push(command.input);
    if (command.constructor.name === 'GetObjectCommand' && command.input.Key.endsWith('/manifest.json')) {
      return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};
    }
    if (command.constructor.name === 'GetObjectCommand') {
      const error = new Error('missing'); error.name = 'NoSuchKey'; error.$metadata = {httpStatusCode:404}; throw error;
    }
    const error = new Error('concurrent create'); error.$metadata = {httpStatusCode:412}; throw error;
  }};
  try {
    await assert.rejects(
      () => advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:7,s3Client}),
      /channel create raced; refusing overwrite/,
    );
    assert.equal(sent.length, 3);
    assert.equal(sent[2].Key, 'resourcepacks/packsets/grounds-global/channels/stable.json');
    assert.equal(sent[2].IfNoneMatch, '*');
    assert.equal(sent[2].IfMatch, undefined);
  } finally { await release.close(); }
});

test('advanceChannel skips an identical retry and conditionally updates a newer pointer', async () => {
  const fixture = await createReleaseFixture();
  const release = await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  const desired = encodeChannel({channel:'stable',sequence:7,publication:release.manifest.publication,manifest:{sha256:release.manifestArtifact.sha256,size:release.manifestArtifact.size}});
  let current = desired; const puts=[];
  const s3Client = {async send(command) {
    if (command.constructor.name === 'GetObjectCommand' && command.input.Key.endsWith('/manifest.json')) return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};
    if (command.constructor.name === 'GetObjectCommand') return {Body:current,ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:'"0123456789abcdef0123456789abcdef"'};
    puts.push(command.input); current=Buffer.from(command.input.Body); return {};
  }};
  try {
    assert.deepEqual(await advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:7,s3Client}),{decision:'unchanged',channel:'stable',sequence:7});
    assert.equal(puts.length,0);
    assert.deepEqual(await advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:8,s3Client}),{decision:'updated',channel:'stable',sequence:8});
    assert.equal(puts.length,1); assert.equal(puts[0].IfMatch,'"0123456789abcdef0123456789abcdef"'); assert.equal(puts[0].IfNoneMatch,undefined);
  } finally { await release.close(); }
});

test('advanceChannel fails closed for stale or same-sequence-different pointers and a precondition race', async () => {
  const fixture = await createReleaseFixture(); const release = await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  let existing=encodeChannel({channel:'stable',sequence:8,publication:release.manifest.publication,manifest:{sha256:release.manifestArtifact.sha256,size:release.manifestArtifact.size}});
  const client = mode => ({async send(command) {
    if(command.constructor.name === 'GetObjectCommand' && command.input.Key.endsWith('/manifest.json')) return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};
    if(command.constructor.name === 'GetObjectCommand') return {Body:existing,ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:'"0123456789abcdef0123456789abcdef"'};
    if(mode==='race') { const error=new Error('race'); error.$metadata={httpStatusCode:412}; throw error; }
    throw new Error('unexpected write');
  }});
  try {
    await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:7,s3Client:client()}),/stale/);
    existing=encodeChannel({channel:'stable',sequence:7,publication:release.manifest.publication,manifest:{sha256:'b'.repeat(64),size:release.manifestArtifact.size}});
    await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:7,s3Client:client()}),/conflicts/);
    await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:9,s3Client:client('race')}),/raced/);
  } finally { await release.close(); }
});

test('advanceChannel rejects unavailable, partial, oversized, byte-drifted, and metadata-drifted immutable targets before channel I/O', async () => {
  const fixture=await createReleaseFixture(); const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  const cases=[
    undefined,
    {Body:Buffer.from('partial'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'},
    {Body:Buffer.concat([fixture.files.get('manifest.json'),Buffer.from('x')]),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'},
    {Body:fixture.files.get('manifest.json'),ContentType:'text/plain',CacheControl:'public, max-age=31536000, immutable'},
    {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'max-age=0'},
  ];
  try { for (const target of cases) { let calls=0; const s3Client={async send(command) { calls+=1; if (!target) throw Object.assign(new Error('missing'),{name:'NoSuchKey',$metadata:{httpStatusCode:404}}); return target; }}; await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'ACCESS_MARKER',secretKey:'SECRET_MARKER',sequence:7,s3Client}),/immutable target manifest/); assert.equal(calls,1); } } finally { await release.close(); }
});

test('advanceChannel propagates target header and body timeouts without credentials', async () => {
  const fixture=await createReleaseFixture(); const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  try {
    for (const send of [() => new Promise(()=>{}), () => ({Body:new PassThrough(),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'})]) {
      const operation=advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'ACCESS_MARKER',secretKey:'SECRET_MARKER',sequence:7,s3Client:{send},timeoutMs:20});
      await assert.rejects(()=>operation,error => /R2 request timed out/.test(error.message) && !/ACCESS_MARKER|SECRET_MARKER/.test(error.message));
    }
  } finally { await release.close(); }
});

test('advanceChannel rejects invalid preflight inputs before network activity', async () => {
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});let calls=0;const s3Client={async send(){calls+=1;}};
  try { for(const overrides of [{channel:'other'},{sequence:0},{sequence:1.5},{bucket:''},{accessKey:''},{secretKey:''},{endpoint:'https://user@example.r2.cloudflarestorage.com'}]) { await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'ACCESS_MARKER',secretKey:'SECRET_MARKER',sequence:7,s3Client,...overrides})); assert.equal(calls,0); } } finally { await release.close(); }
});

test('advanceChannel refuses malformed pointer bytes or ETags before every conditional PUT', async () => {
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  const valid=encodeChannel({channel:'stable',sequence:7,publication:release.manifest.publication,manifest:{sha256:release.manifestArtifact.sha256,size:release.manifestArtifact.size}}).toString();
  const malformed=[Buffer.from([0xc3]),Buffer.concat([Buffer.from([0xef,0xbb,0xbf]),Buffer.from(valid)]),Buffer.from(valid+'x'),Buffer.from(valid.replace('"channel": "stable",','"channel": "stable",\n  "channel": "stable",')),Buffer.from(valid.replace('"packSet": "grounds-global"','"packSet": "other"')),Buffer.from(valid.replace('"channel": "stable"','"channel": "edge"')),Buffer.from(valid.replace('"type": "release"','"type": "build"')),Buffer.from(valid.replace('v0.1.0','v01.0')),Buffer.from(valid.replace(release.manifestArtifact.sha256,'B'.repeat(64))),Buffer.from(valid.replace('"size": '+release.manifestArtifact.size,'"size": 0')),Buffer.alloc(1024*1024+1)];
  try { for(const body of malformed) {let puts=0;const s3Client={async send(command){if(command.constructor.name==='PutObjectCommand')puts+=1;if(command.constructor.name==='GetObjectCommand'&&command.input.Key.endsWith('/manifest.json'))return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};return {Body:body,ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:'"0123456789abcdef0123456789abcdef"'};}};await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:8,s3Client}));assert.equal(puts,0);} for(const etag of [undefined,'','observed','"not-an-etag"']){let puts=0;const s3Client={async send(command){if(command.constructor.name==='PutObjectCommand')puts+=1;if(command.input.Key.endsWith('/manifest.json'))return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};return {Body:Buffer.from(valid),ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:etag};}};await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:8,s3Client}),/metadata/);assert.equal(puts,0);}} finally {await release.close();}
});

test('advanceChannel refuses a canonical opposite-channel pointer at the requested key without PUT', async () => {
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  const opposite=encodeChannel({channel:'edge',sequence:7,publication:{type:'build',id:'0123456789abcdef0123456789abcdef01234567'},manifest:{sha256:'a'.repeat(64),size:1}});let puts=0;
  const s3Client={async send(command){if(command.constructor.name==='PutObjectCommand')puts+=1;if(command.constructor.name==='GetObjectCommand'&&command.input.Key.endsWith('/manifest.json'))return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};return {Body:opposite,ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:'"0123456789abcdef0123456789abcdef"'};}};
  try{await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:8,s3Client}),/does not match requested channel/);assert.equal(puts,0);}finally{await release.close();}
});

test('advanceChannel creates the exact Edge key and immutable build target', async () => {
  const fixture=await createReleaseFixture({type:'build'});const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});const seen=[];const s3Client={async send(command){seen.push(command.input);if(command.constructor.name==='GetObjectCommand'&&command.input.Key.endsWith('/manifest.json'))return {Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};if(command.constructor.name==='GetObjectCommand')throw Object.assign(new Error('missing'),{name:'NoSuchKey',$metadata:{httpStatusCode:404}});return {};}};
  try{assert.deepEqual(await advanceChannel({channel:'edge',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:42,s3Client}),{decision:'created',channel:'edge',sequence:42});assert.equal(seen[0].Key,`resourcepacks/packsets/grounds-global/builds/${release.manifest.publication.id}/manifest.json`);assert.equal(seen[2].Key,'resourcepacks/packsets/grounds-global/channels/edge.json');assert.match(Buffer.from(seen[2].Body).toString(),/"channel": "edge"/);}finally{await release.close();}
});

test('advanceChannel rejects public release mutation before I/O and retains private target state across awaits', async () => {
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});
  try { release.artifacts[3].key='resourcepacks/attacker';let calls=0;await assert.rejects(()=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:7,s3Client:{async send(){calls+=1;}}}),/release artifact contract mismatch/);assert.equal(calls,0); } finally {await release.close();}
  const second=await createReleaseFixture();const safe=await loadRelease({manifestFile:`${second.root}/manifest.json`,releaseDirectory:second.root});const seen=[];
  const s3Client={async send(command){seen.push(command.input);if(command.constructor.name==='GetObjectCommand'&&command.input.Key.endsWith('/manifest.json')){safe.manifest.publication.id='v9.9.9';safe.artifacts[3].key='resourcepacks/attacker';return {Body:second.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};}if(command.constructor.name==='GetObjectCommand')throw Object.assign(new Error('missing'),{name:'NoSuchKey',$metadata:{httpStatusCode:404}});return {};}};
  try{await advanceChannel({channel:'stable',release:safe,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'a',secretKey:'s',sequence:7,s3Client});assert.equal(seen[0].Key,'resourcepacks/packsets/grounds-global/releases/v0.1.0/manifest.json');assert.equal(seen[2].Key,'resourcepacks/packsets/grounds-global/channels/stable.json');assert.match(Buffer.from(seen[2].Body).toString(),/v0\.1\.0/);}finally{await safe.close();}
});

test('advanceChannel times out current headers and bodies plus create and update writes', async () => {
  const fixture=await createReleaseFixture();const release=await loadRelease({manifestFile:`${fixture.root}/manifest.json`,releaseDirectory:fixture.root});const target={Body:fixture.files.get('manifest.json'),ContentType:'application/json',CacheControl:'public, max-age=31536000, immutable'};const current=encodeChannel({channel:'stable',sequence:1,publication:release.manifest.publication,manifest:{sha256:release.manifestArtifact.sha256,size:release.manifestArtifact.size}});
  const operation=s3Client=>advanceChannel({channel:'stable',release,bucket:'packs',endpoint:'https://example.r2.cloudflarestorage.com',accessKey:'ACCESS_MARKER',secretKey:'SECRET_MARKER',sequence:2,s3Client,timeoutMs:20});
  try {
    for(const send of [async command=>command.input.Key.endsWith('/manifest.json')?target:new Promise(()=>{}),async command=>command.input.Key.endsWith('/manifest.json')?target:{Body:new PassThrough(),ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:'"0123456789abcdef0123456789abcdef"'},async command=>{if(command.input.Key.endsWith('/manifest.json'))return target;if(command.constructor.name==='GetObjectCommand')throw Object.assign(new Error('missing'),{name:'NoSuchKey',$metadata:{httpStatusCode:404}});return new Promise(()=>{});},async command=>{if(command.input.Key.endsWith('/manifest.json'))return target;if(command.constructor.name==='GetObjectCommand')return {Body:current,ContentType:'application/json; charset=utf-8',CacheControl:'public, max-age=30, must-revalidate',ETag:'"0123456789abcdef0123456789abcdef"'};return new Promise(()=>{}); }]) await assert.rejects(()=>operation({send}),error=>/R2 request timed out/.test(error.message)&&!/ACCESS_MARKER|SECRET_MARKER/.test(error.message));
  } finally {await release.close();}
});
