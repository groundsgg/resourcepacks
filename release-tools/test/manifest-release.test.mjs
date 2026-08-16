import assert from 'node:assert/strict';
import { symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';

import { loadRelease, openVerifiedArtifact } from '../src/manifest.mjs';
import { canonicalJson, createReleaseFixture } from './fixtures.mjs';

test('loadRelease binds the canonical manifest to exactly four measured regular files', async () => {
  const fixture = await createReleaseFixture();
  const release = await loadRelease({
    manifestFile: join(fixture.root, 'manifest.json'),
    releaseDirectory: fixture.root,
  });

  assert.deepEqual(release.artifacts.map(artifact => artifact.name), [...fixture.files.keys()].sort());
  assert.deepEqual(release.artifacts.map(artifact => artifact.key), [
    ...fixture.manifest.packs.map(pack => new URL(pack.url).pathname.slice(1)),
    `resourcepacks/packsets/grounds-global/releases/v${fixture.manifest.version}/${fixture.manifest.catalog.file}`,
    `resourcepacks/packsets/grounds-global/releases/v${fixture.manifest.version}/manifest.json`,
  ].sort());
  await release.close();
});

test('loadRelease binds the schema-v2 build files to their full-commit immutable root', async () => {
  const fixture = await createReleaseFixture({type:'build'});
  const release = await loadRelease({manifestFile:join(fixture.root,'manifest.json'),releaseDirectory:fixture.root});
  assert.equal(release.manifest.publication.type,'build');
  assert.deepEqual(release.artifacts.map(artifact=>artifact.key),[
    ...fixture.manifest.packs.map(pack=>new URL(pack.url).pathname.slice(1)),
    `resourcepacks/packsets/grounds-global/builds/${fixture.manifest.provenance.commit}/${fixture.manifest.catalog.file}`,
    `resourcepacks/packsets/grounds-global/builds/${fixture.manifest.provenance.commit}/manifest.json`,
  ].sort());
  await release.close();
});

test('loadRelease rejects noncanonical, unknown, duplicate, and semantically drifted manifest data', async () => {
  const cases = [
    text => text.replace('"schemaVersion": 2', '"schemaVersion": 1'),
    text => text.replace('"schemaVersion": 2', '"extra": true,\n  "schemaVersion": 2'),
    text => text.replace('"order": 0', '"order": 1'),
    text => text.replace('grounds-content-pack-v0.1.0.zip', 'grounds-platform-pack-v0.1.0.zip'),
    text => text.replace('"coordinate": "gg.grounds:resourcepacks-catalog:0.1.0"', '"coordinate": "gg.grounds:other:0.1.0"'),
  ];
  for (const mutate of cases) {
    const fixture = await createReleaseFixture();
    const manifestFile = join(fixture.root, 'manifest.json');
    await writeFile(manifestFile, mutate(canonicalJson(fixture.manifest)));
    await assert.rejects(() => loadRelease({ manifestFile, releaseDirectory: fixture.root }), /manifest/i);
  }
});

test('loadRelease rejects extra, missing, symlinked, and digest-drifted release artifacts', async () => {
  const extra = await createReleaseFixture();
  await writeFile(join(extra.root, 'extra.txt'), 'not releasable');
  await assert.rejects(() => loadRelease({manifestFile:join(extra.root,'manifest.json'),releaseDirectory:extra.root}), /exactly four/i);

  const changed = await createReleaseFixture();
  const contentName = new URL(changed.manifest.packs[0].url).pathname.split('/').at(-1);
  await writeFile(join(changed.root, contentName), 'changed');
  await assert.rejects(() => loadRelease({manifestFile:join(changed.root,'manifest.json'),releaseDirectory:changed.root}), /digest|size/i);

  const linked = await createReleaseFixture();
  const platformName = new URL(linked.manifest.packs[1].url).pathname.split('/').at(-1);
  const target = `${linked.root}-outside.zip`;
  await writeFile(target, linked.files.get(platformName));
  await writeFile(join(linked.root, platformName), 'temporary');
  const { unlink } = await import('node:fs/promises');
  await unlink(join(linked.root, platformName));
  await symlink(target, join(linked.root, platformName));
  await assert.rejects(() => loadRelease({manifestFile:join(linked.root,'manifest.json'),releaseDirectory:linked.root}), /symbolic link|regular file/i);
});

test('readManifest rejects an oversized manifest before parsing it',async()=>{
  const fixture=await createReleaseFixture();
  await writeFile(join(fixture.root,'manifest.json'),' '.repeat(1024*1024+1));
  await assert.rejects(()=>loadRelease({manifestFile:join(fixture.root,'manifest.json'),releaseDirectory:fixture.root}),/manifest is too large/i);
});

test('readManifest applies fixed product size caps independent of manifest claims',async()=>{
  const fixture=await createReleaseFixture();
  fixture.manifest.packs[0].size=Number.MAX_SAFE_INTEGER;
  await writeFile(join(fixture.root,'manifest.json'),canonicalJson(fixture.manifest));
  await assert.rejects(()=>loadRelease({manifestFile:join(fixture.root,'manifest.json'),releaseDirectory:fixture.root}),/product limit/i);
});

test('loadRelease binds manifest parsing, digest, and later upload reads to one private snapshot',async()=>{
  for(const replacement of ['in-place','replace']){
    const fixture=await createReleaseFixture();const manifestPath=join(fixture.root,'manifest.json');const original=fixture.files.get('manifest.json');
    const release=await loadRelease({manifestFile:manifestPath,releaseDirectory:fixture.root});
    if(replacement==='replace'){const {rename}=await import('node:fs/promises');const next=`${manifestPath}.next`;await writeFile(next,'attacker replacement');await rename(next,manifestPath);}else await writeFile(manifestPath,'attacker in-place');
    const verified=await openVerifiedArtifact(release.manifestArtifact);const chunks=[];for await(const chunk of verified.stream())chunks.push(Buffer.from(chunk));await verified.close();
    assert.deepEqual(Buffer.concat(chunks),original,replacement);
    await release.close();
  }
});
