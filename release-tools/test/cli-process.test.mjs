import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
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

test('all four command files reject unknown arguments with a stable process contract', async () => {
  for (const name of ['r2-create-or-compare', 'verify-cdn', 'maven-create-or-compare', 'release-assets-create-or-compare']) {
    const result = await run(name, ['--unknown', 'value']);
    assert.equal(result.code, 2, name);
    assert.equal(result.stdout, '', name);
    assert.equal(result.stderr, 'error: invalid command arguments\n', name);
  }
});

test('r2 CLI derives exactly two immutable object keys from the bound release', async t => {
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
  assert.equal(result.stdout, 'created=2 identical=0\n');
  assert.equal(requests.length, 2);
  assert.deepEqual(requests.map(request => new URL(request.url,http.url).pathname).sort(), fixture.manifest.packs.map(pack => `/packs/resourcepacks/${pack.role}/${pack.sha1}.zip`).sort());
  assert.ok(requests.every(request => request.method === 'PUT' && request.headers['if-none-match'] === '*'));
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, /ACCESS_MARKER|SECRET_MARKER/);
});

test('CDN CLI uses only the supplied base URL and verifies exactly both manifest packs', async t => {
  const fixture = await createReleaseFixture();
  const seen = [];
  const http = await fakeHttp((request, response) => {
    seen.push(request.url);
    const pack = fixture.manifest.packs.find(candidate => request.url === `/resourcepacks/${candidate.role}/${candidate.sha1}.zip`);
    const bytes = fixture.files.get(`grounds-${pack.role}-${pack.sha1}.zip`);
    response.writeHead(200, {'Content-Type':'application/zip','Cache-Control':'immutable, max-age=31536000, public'}).end(bytes);
  });
  t.after(http.close);
  const result = await run('verify-cdn', ['--manifest',join(fixture.root,'manifest.json'),'--base-url',http.url]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.stdout, 'verified=2\n');
  assert.equal(seen.length, 2);
});

test('Maven CLI gates the exact manifest coordinate and prints a workflow-safe decision', async t => {
  const fixture = await createReleaseFixture();
  const versionDirectory = join(fixture.root, 'maven', 'gg', 'grounds', 'resourcepacks-catalog', '0.1.0');
  await mkdir(versionDirectory, {recursive:true});
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0.pom'), '<project/>');
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0.jar'), fixture.files.get('grounds-resourcepacks-catalog-0.1.0.jar'));
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0-sources.jar'), 'sources');
  await writeFile(join(versionDirectory, 'resourcepacks-catalog-0.1.0.module'), '{}');
  const log=`${fixture.root}-maven.log`;const preload=fileURLToPath(new URL('./maven-fetch-preload.mjs',import.meta.url));
  const result = await run('maven-create-or-compare', [
    '--manifest',join(fixture.root,'manifest.json'),'--staging-directory',join(fixture.root,'maven'),
    '--username','actor','--token','TOKEN_MARKER',
  ],{NODE_OPTIONS:`--import=${preload}`,MAVEN_FAKE_LOG:log});
  assert.equal(result.code,0,result.stderr);
  assert.match(result.stdout,/^publish-directory=\/tmp\//);
  assert.deepEqual((await readFile(log,'utf8')).trim().split('\n').sort(), [
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0.jar',
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0-sources.jar',
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0.module',
    '/groundsgg/resourcepacks/gg/grounds/resourcepacks-catalog/0.1.0/resourcepacks-catalog-0.1.0.pom',
  ].sort());
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, /TOKEN_MARKER/);
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
