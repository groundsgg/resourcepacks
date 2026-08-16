import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { GetObjectCommand, PutObjectCommand, S3ServiceException } from '@aws-sdk/client-s3';
import { encodeChannel } from '../src/channel.mjs';
import { prepareEdge } from '../src/prepare-edge.mjs';
import { loadRelease } from '../src/manifest.mjs';
import { r2ReleaseCreateOrCompare } from '../src/r2-create-or-compare.mjs';
import { canonicalJson, createReleaseFixture } from './fixtures.mjs';

test('prepare-edge package bin rejects incomplete CLI input with usage exit status', () => {
  const result = spawnSync(process.execPath, ['src/prepare-edge.mjs'], { encoding: 'utf8' });
  assert.equal(result.status, 2);
});

function object(bytes, contentType = 'application/json', cacheControl = 'public, max-age=31536000, immutable') {
  return { Body: Readable.from(bytes), ContentType: contentType, CacheControl: cacheControl, ETag: '"0123456789abcdef0123456789abcdef"' };
}

test('prepare-edge reuses only validated historical URLs while retaining current local names', async () => {
  const oldCommit = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
  const current = await createReleaseFixture({ type: 'build', commit: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' });
  const old = await createReleaseFixture({ type: 'build', commit: oldCommit });
  const oldManifest = Buffer.from(canonicalJson(old.manifest));
  const channel = encodeChannel({ channel: 'edge', sequence: 7, publication: old.manifest.publication, manifest: { sha256: (await import('node:crypto')).createHash('sha256').update(oldManifest).digest('hex'), size: oldManifest.length } });
  const requests = [];
  const s3Client = { async send(command) {
    assert.ok(command instanceof GetObjectCommand); requests.push(command.input.Key);
    if (command.input.Key.endsWith('/channels/edge.json')) return object(channel, 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate');
    if (command.input.Key.endsWith(`/builds/${oldCommit}/manifest.json`)) return object(oldManifest);
    throw Object.assign(new Error('unexpected key'), { name: 'NoSuchKey', $metadata: { httpStatusCode: 404 } });
  }};
  const output = await mkdtemp(join(tmpdir(), 'resourcepacks-prepared-edge-'));
  const absent = join(output, 'prepared');
  const result = await prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: absent, bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client });
  assert.equal(result.reused, 2);
  const prepared = JSON.parse(await readFile(join(absent, 'manifest.json'), 'utf8'));
  assert.deepEqual(prepared.packs.map(pack => pack.url), old.manifest.packs.map(pack => pack.url));
  assert.deepEqual((await (await import('node:fs/promises')).readdir(absent)).sort(), [current.manifest.catalog.file, ...current.manifest.packs.map(pack => new URL(pack.url).pathname.split('/').at(-1)), 'manifest.json'].sort());
  assert.deepEqual(requests, ['resourcepacks/packsets/grounds-global/channels/edge.json', `resourcepacks/packsets/grounds-global/builds/${oldCommit}/manifest.json`]);
});

test('prepare-edge treats NoSuchKey as zero reuse and malformed existing state fails before output', async () => {
  const current = await createReleaseFixture({ type: 'build', commit: 'cccccccccccccccccccccccccccccccccccccccc' });
  const missing = { async send() { throw Object.assign(new Error('missing'), { name: 'NoSuchKey', $metadata: { httpStatusCode: 404 } }); } };
  const root = await mkdtemp(join(tmpdir(), 'resourcepacks-prepared-edge-'));
  const output = join(root, 'prepared');
  assert.deepEqual(await prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: output, bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client: missing }), { reused: 0 });
  const malformed = { async send() { return object(Buffer.from('{}'), 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate'); } };
  await assert.rejects(() => prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: join(root, 'must-stay-absent'), bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client: malformed }), /channel/);
  const hanging = { async send() { return new Promise(() => {}); } };
  await assert.rejects(() => prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: join(root, 'timeout-stays-absent'), bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client: hanging, timeoutMs: 20 }), /timed out/);
  const stuckBody = { async send() { return { Body: new Readable({ read() {} }), ContentType: 'application/json; charset=utf-8', CacheControl: 'public, max-age=30, must-revalidate', ETag: '"0123456789abcdef0123456789abcdef"' }; } };
  await assert.rejects(() => prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: join(root, 'body-timeout-stays-absent'), bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client: stuckBody, timeoutMs: 20 }), /timed out/);
});

test('changed pack uses the current root while unchanged pack creates no duplicate current-root object', async () => {
  const oldCommit = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
  const currentCommit = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';
  const old = await createReleaseFixture({ type: 'build', commit: oldCommit });
  const current = await createReleaseFixture({ type: 'build', commit: currentCommit });
  const content = Buffer.from('changed content fixture');
  const contentSha1 = (await import('node:crypto')).createHash('sha1').update(content).digest('hex');
  const contentSha256 = (await import('node:crypto')).createHash('sha256').update(content).digest('hex');
  current.manifest.packs[0].sha1 = contentSha1; current.manifest.packs[0].sha256 = contentSha256; current.manifest.packs[0].size = content.length;
  await writeFile(join(current.root, current.manifest.packs[0].url.split('/').at(-1)), content);
  await writeFile(join(current.root, 'manifest.json'), canonicalJson(current.manifest));
  const oldBytes = Buffer.from(canonicalJson(old.manifest));
  const channel = encodeChannel({ channel: 'edge', sequence: 7, publication: old.manifest.publication, manifest: { sha256: (await import('node:crypto')).createHash('sha256').update(oldBytes).digest('hex'), size: oldBytes.length } });
  const readClient = { async send(command) {
    if (command.input.Key.endsWith('/channels/edge.json')) return object(channel, 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate');
    return object(oldBytes);
  }};
  const root = await mkdtemp(join(tmpdir(), 'resourcepacks-prepared-edge-')); const preparedDir = join(root, 'prepared');
  await prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: preparedDir, bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client: readClient });
  const prepared = await loadRelease({ manifestFile: join(preparedDir, 'manifest.json'), releaseDirectory: preparedDir });
  const putKeys = []; const putClient = { async send(command) {
    if (command instanceof PutObjectCommand) { putKeys.push(command.input.Key); if (command.input.Key.includes(`/builds/${oldCommit}/grounds-platform-`)) throw new S3ServiceException({ name: 'PreconditionFailed', $metadata: { httpStatusCode: 412 } }); return {}; }
    if (command instanceof GetObjectCommand) return object(old.files.get(old.manifest.packs[1].url.split('/').at(-1)), 'application/zip');
    throw new Error('unexpected');
  }};
  try { await r2ReleaseCreateOrCompare({ release: prepared, endpoint: 'https://example.r2.cloudflarestorage.com', bucket: 'packs', accessKey: 'a', secretKey: 's', client: putClient }); }
  finally { await prepared.close(); }
  assert.ok(putKeys.includes(`resourcepacks/packsets/grounds-global/builds/${currentCommit}/${current.manifest.packs[0].url.split('/').at(-1)}`));
  assert.ok(!putKeys.some(key => key.includes(`/builds/${currentCommit}/grounds-platform-`)));
});

test('target header/body timeouts and invalid target identity fail before prepared output', async () => {
  const oldCommit = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
  const current = await createReleaseFixture({ type: 'build', commit: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' });
  const old = await createReleaseFixture({ type: 'build', commit: oldCommit });
  const valid = Buffer.from(canonicalJson(old.manifest));
  const channel = encodeChannel({ channel: 'edge', sequence: 7, publication: old.manifest.publication, manifest: { sha256: (await import('node:crypto')).createHash('sha256').update(valid).digest('hex'), size: valid.length } });
  const root = await mkdtemp(join(tmpdir(), 'resourcepacks-prepared-edge-'));
  const call = (s3Client, name) => prepareEdge({ manifestFile: join(current.root, 'manifest.json'), releaseDirectory: current.root, outputDirectory: join(root, name), bucket: 'packs', endpoint: 'https://example.r2.cloudflarestorage.com', accessKey: 'a', secretKey: 's', s3Client, timeoutMs: 20 });
  const header = { calls: 0, async send() { if (this.calls++ === 0) return object(channel, 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate'); return new Promise(() => {}); } };
  await assert.rejects(() => call(header, 'target-header'), /timed out/);
  const body = { calls: 0, async send() { if (this.calls++ === 0) return object(channel, 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate'); return object(new Readable({ read() {} })); } };
  await assert.rejects(() => call(body, 'target-body'), /timed out/);
  const wrong = structuredClone(old.manifest); wrong.provenance.commit = 'cccccccccccccccccccccccccccccccccccccccc';
  const wrongBytes = Buffer.from(canonicalJson(wrong));
  const wrongChannel = encodeChannel({ channel: 'edge', sequence: 7, publication: old.manifest.publication, manifest: { sha256: (await import('node:crypto')).createHash('sha256').update(wrongBytes).digest('hex'), size: wrongBytes.length } });
  const badIdentity = { calls: 0, async send() { return this.calls++ === 0 ? object(wrongChannel, 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate') : object(wrongBytes); } };
  await assert.rejects(() => call(badIdentity, 'wrong-target'), /target manifest is invalid|does not bind/);
  const missingTarget = { calls: 0, async send() { if (this.calls++ === 0) return object(channel, 'application/json; charset=utf-8', 'public, max-age=30, must-revalidate'); throw Object.assign(new Error('missing'), { name: 'NoSuchKey', $metadata: { httpStatusCode: 404 } }); } };
  await assert.rejects(() => call(missingTarget, 'missing-target'), /unavailable/);
});
