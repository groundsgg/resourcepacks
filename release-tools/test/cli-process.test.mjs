import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { createReleaseFixture } from './fixtures.mjs';
import { fakeHttp } from './fake-http.mjs';

const source = name => fileURLToPath(new URL(`../src/${name}.mjs`, import.meta.url));

function run(name, args, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [source(name), ...args], { env: {PATH:process.env.PATH,TMPDIR:process.env.TMPDIR,...extraEnv} });
    let stdout = ''; let stderr = '';
    child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', code => resolve({ code, stdout, stderr }));
  });
}

test('all five command files reject unknown arguments with a stable process contract', async () => {
  for (const name of ['r2-create-or-compare', 'r2-channel-advance', 'verify-cdn', 'maven-create-or-compare', 'release-assets-create-or-compare']) {
    const result = await run(name, ['--unknown', 'value']);
    assert.equal(result.code, 2, name);
    assert.equal(result.stdout, '', name);
    assert.equal(result.stderr, 'error: invalid command arguments\n', name);
  }
});

test('channel CLI rejects duplicate, empty, missing, invalid channel, and invalid sequence as usage errors', async () => {
  const base=['--channel','stable','--manifest','x','--release-directory','x','--bucket','packs','--endpoint','https://example.test','--access-key','a','--secret-key','s','--sequence','7'];
  for(const [args,stderr] of [
    [[...base,'--channel','stable'],'error: invalid command arguments\n'],
    [base.map(value=>value==='stable'?'':value),'error: invalid command arguments\n'],
    [base.slice(0,-2),'error: missing required command arguments\n'],
    [base.map(value=>value==='stable'?'other':value),'error: invalid command arguments\n'],
    [base.map(value=>value==='7'?'0':value),'error: invalid command arguments\n'],
  ]) { const result=await run('r2-channel-advance',args);assert.equal(result.code,2);assert.equal(result.stdout,'');assert.equal(result.stderr,stderr); }
});

test('channel CLI creates Stable and updates Edge with exact workflow stdout', async t => {
  const stable=await createReleaseFixture();const edge=await createReleaseFixture({type:'build'});let edgeReads=0;
  const http=await fakeHttp(async(request,response)=>{const key=decodeURIComponent(new URL(request.url,'http://localhost').pathname).replace(/^\/packs\//,'');const fixture=key.includes('/builds/')?edge:stable;if(request.method==='GET'&&key.endsWith('/manifest.json'))return response.writeHead(200,{'Content-Type':'application/json','Cache-Control':'public, max-age=31536000, immutable'}).end(fixture.files.get('manifest.json'));if(request.method==='GET'&&key.endsWith('/channels/stable.json'))return response.writeHead(404,{'Content-Type':'application/xml'}).end('<Error><Code>NoSuchKey</Code></Error>');if(request.method==='GET'&&key.endsWith('/channels/edge.json')){edgeReads+=1;return response.writeHead(200,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'public, max-age=30, must-revalidate','ETag':'"0123456789abcdef0123456789abcdef"'}).end('{\n  "channel": "edge",\n  "manifest": {\n    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",\n    "size": 1,\n    "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/0123456789abcdef0123456789abcdef01234567/manifest.json"\n  },\n  "packSet": "grounds-global",\n  "schemaVersion": 2,\n  "sequence": 1,\n  "target": {\n    "id": "0123456789abcdef0123456789abcdef01234567",\n    "type": "build"\n  }\n}\n');}if(request.method==='PUT'&&/\/channels\/(stable|edge)\.json$/.test(key)){for await(const _ of request){}return response.writeHead(200).end();}for await(const _ of request){}response.writeHead(500).end();});t.after(http.close);
  const invoke=(fixture,channel,sequence)=>run('r2-channel-advance',['--channel',channel,'--manifest',join(fixture.root,'manifest.json'),'--release-directory',fixture.root,'--bucket','packs','--endpoint',http.url,'--access-key','ACCESS_MARKER','--secret-key','SECRET_MARKER','--sequence',String(sequence)]);
  const created=await invoke(stable,'stable',7);assert.equal(created.code,0,created.stderr);assert.equal(created.stdout,'created channel=stable sequence=7\n');const updated=await invoke(edge,'edge',2);assert.equal(updated.code,0,updated.stderr);assert.equal(updated.stdout,'updated channel=edge sequence=2\n');assert.equal(edgeReads,1);assert.doesNotMatch(`${created.stderr}${updated.stderr}`,/ACCESS_MARKER|SECRET_MARKER/);
});

test('channel CLI operation failure exits one and never exposes R2 credentials', async () => {
  const fixture=await createReleaseFixture();const result=await run('r2-channel-advance',['--channel','stable','--manifest',join(fixture.root,'manifest.json'),'--release-directory',fixture.root,'--bucket','packs','--endpoint','http://127.0.0.1:1','--access-key','ACCESS_MARKER','--secret-key','SECRET_MARKER','--sequence','7']);
  assert.equal(result.code,1);assert.equal(result.stdout,'');assert.match(result.stderr,/^error: R2 channel advancement failed/);assert.doesNotMatch(result.stderr,/ACCESS_MARKER|SECRET_MARKER/);
});

test('r2 CLI derives exactly four readable immutable object keys from the bound release', async t => {
  const fixture = await createReleaseFixture();
  const requests = [];
  const http = await fakeHttp(async (request, response) => {
    requests.push({ method: request.method, url: request.url, headers: request.headers });
    for await (const _chunk of request) { /* consume */ }
    response.writeHead(200).end();
  });
  t.after(http.close);
  const result = await run('r2-create-or-compare', [
    '--manifest', join(fixture.root, 'manifest.json'), '--release-directory', fixture.root,
    '--bucket', 'packs', '--endpoint', http.url, '--access-key', 'ACCESS_MARKER', '--secret-key', 'SECRET_MARKER',
  ]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.stdout, 'created=4 identical=0\n');
  assert.equal(requests.length, 4);
  assert.deepEqual(requests.map(request => new URL(request.url,http.url).pathname).sort(), [
    ...fixture.manifest.packs.map(pack => `/packs${new URL(pack.url).pathname}`),
    `/packs/resourcepacks/packsets/grounds-global/releases/v0.1.0/${fixture.manifest.catalog.file}`,
    '/packs/resourcepacks/packsets/grounds-global/releases/v0.1.0/manifest.json',
  ].sort());
  assert.ok(requests.every(request => request.method === 'PUT' && request.headers['if-none-match'] === '*'));
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, /ACCESS_MARKER|SECRET_MARKER/);
});

test('CDN CLI uses only the supplied base URL and verifies exactly four manifest artifacts', async t => {
  const fixture = await createReleaseFixture();
  const seen = [];
  const http = await fakeHttp((request, response) => {
    seen.push(request.url);
    const pack = fixture.manifest.packs.find(candidate => request.url === new URL(candidate.url).pathname);
    const name = pack ? new URL(pack.url).pathname.split('/').at(-1) : request.url.split('/').at(-1);
    const bytes = fixture.files.get(name);
    response.writeHead(200, {'Content-Type':name.endsWith('.zip')?'application/zip':name.endsWith('.jar')?'application/java-archive':'application/json','Cache-Control':'immutable, max-age=31536000, public'}).end(bytes);
  });
  t.after(http.close);
  const result = await run('verify-cdn', ['--manifest',join(fixture.root,'manifest.json'),'--release-directory',fixture.root,'--base-url',http.url]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.stdout, 'verified=4\n');
  assert.equal(seen.length, 4);
});

test('Maven CLI gates the exact manifest coordinate and prints a workflow-safe decision', async t => {
  const fixture = await createReleaseFixture();
  const versionDirectory = join(fixture.root, 'maven', 'gg', 'grounds', 'resourcepacks-catalog', '0.1.0');
  await mkdir(versionDirectory, {recursive:true});
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0.pom'), '<project/>');
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0.jar'), fixture.files.get(fixture.manifest.catalog.file));
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0-sources.jar'), 'sources');
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0.module'), '{}');
  const log=`${fixture.root}-maven.log`;const preload=fileURLToPath(new URL('./maven-fetch-preload.mjs',import.meta.url));
  const before=(await readdir(tmpdir())).filter(name=>name.startsWith('grounds-maven-publish-')).sort();
  assert.deepEqual(before,[]);
  const args=[
    '--manifest',join(fixture.root,'manifest.json'),'--staging-directory',join(fixture.root,'maven'),
    '--username','actor','--token','TOKEN_MARKER',
  ];
  const result = await run('maven-create-or-compare',args,{NODE_OPTIONS:`--import=${preload}`,MAVEN_FAKE_LOG:log});
  assert.equal(result.code,0,result.stderr);
  assert.equal(result.stdout,'publish\n');
  assert.deepEqual((await readFile(log,'utf8')).trim().split('\n').sort(), [
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0.jar',
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0-sources.jar',
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0.module',
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0.pom',
  ].sort());
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, /TOKEN_MARKER/);
  const same=await run('maven-create-or-compare',args,{NODE_OPTIONS:`--import=${preload}`,MAVEN_FAKE_LOG:`${log}.same`,MAVEN_FAKE_MODE:'same',MAVEN_FAKE_ROOT:join(fixture.root,'maven')});
  assert.equal(same.code,0,same.stderr);assert.equal(same.stdout,'skip\n');
  const failed=await run('maven-create-or-compare',args,{NODE_OPTIONS:`--import=${preload}`,MAVEN_FAKE_LOG:`${log}.error`,MAVEN_FAKE_MODE:'error'});
  assert.equal(failed.code,1);assert.equal(failed.stdout,'');assert.match(failed.stderr,/Maven comparison returned HTTP 503/);
  assert.deepEqual((await readdir(tmpdir())).filter(name=>name.startsWith('grounds-maven-publish-')).sort(),[]);
});

test('GitHub Release CLI uploads exactly the four manifest-bound missing assets without clobber', async () => {
  const fixture = await createReleaseFixture();
  const log=`${fixture.root}-github-uploads.log`;
  const preload=fileURLToPath(new URL('./github-fetch-preload.mjs',import.meta.url));
  const result = await run('release-assets-create-or-compare', [
    '--manifest',join(fixture.root,'manifest.json'),'--release-directory',fixture.root,
    '--token','TOKEN_MARKER',
  ],{NODE_OPTIONS:`--import=${preload}`,GITHUB_FAKE_LOG:log});
  assert.equal(result.code,0,result.stderr);
  assert.equal(result.stdout,'uploaded=4 identical=0\n');
  assert.deepEqual((await readFile(log,'utf8')).trim().split('\n').sort(), [...fixture.files.keys()].sort());
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, /TOKEN_MARKER/);
});

test('CLI operation failures keep all supplied credential values out of diagnostics',async()=>{
  const fixture=await createReleaseFixture();
  const result=await run('r2-create-or-compare',['--manifest',join(fixture.root,'manifest.json'),'--release-directory',fixture.root,'--bucket','packs','--endpoint','http://127.0.0.1:1','--access-key','ACCESS_MARKER','--secret-key','SECRET_MARKER']);
  assert.equal(result.code,1);
  assert.equal(result.stdout,'');
  assert.match(result.stderr,/^error: R2 upload failed/);
  assert.doesNotMatch(result.stderr,/ACCESS_MARKER|SECRET_MARKER/);
});
