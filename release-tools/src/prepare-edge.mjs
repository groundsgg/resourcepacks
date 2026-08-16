#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, mkdir, open, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import { GetObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { NodeHttpHandler } from '@smithy/node-http-handler';
import { assertNoSymlinkComponents, assertReleaseArtifactContract, canonicalManifestText, loadRelease, openVerifiedArtifact, parseManifestBytes } from './manifest.mjs';
import { decodeChannel } from './channel.mjs';
import { CliUsageError, runCli, strictArgs } from './cli.mjs';
import { IMMUTABLE_CACHE_CONTROL } from './r2-create-or-compare.mjs';
import { DEFAULT_NETWORK_TIMEOUT_MS, OperationTimeoutError, withTimeout } from './timeout.mjs';

const CHANNEL_KEY = 'resourcepacks/packsets/grounds-global/channels/edge.json';
const CHANNEL_TYPE = 'application/json; charset=utf-8';
const MAX = 1024 * 1024;
const missing = error => error?.name === 'NoSuchKey' || error?.$metadata?.httpStatusCode === 404;
const fail = detail => { throw new Error(`Edge preparation failed: ${detail}`); };

function client({ endpoint, accessKey, secretKey }) {
  const url = new URL(endpoint);
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || (url.protocol === 'http:' && !['127.0.0.1', 'localhost'].includes(url.hostname))) fail('R2 endpoint is invalid or insecure');
  return new S3Client({ endpoint: url.href, region: 'auto', forcePathStyle: true, maxAttempts: 1, credentials: { accessKeyId: accessKey, secretAccessKey: secretKey }, logger: { debug(){}, info(){}, warn(){}, error(){} }, requestHandler: new NodeHttpHandler({ connectionTimeout: 15_000, socketTimeout: 15_000 }) });
}

async function bytes(body, limit = MAX, timeoutMs = DEFAULT_NETWORK_TIMEOUT_MS) {
  const parts = []; let size = 0;
  const stream = Readable.from(body);
  return withTimeout('R2 request timed out while reading Edge state', async ({onTimeout}) => {
    onTimeout(() => { stream.destroy(); body.destroy?.(); });
    for await (const part of stream) { const value = Buffer.from(part); size += value.length; if (size > limit) fail('remote manifest exceeds parser limit'); parts.push(value); }
    return Buffer.concat(parts);
  }, timeoutMs);
}

async function request(s3, command, timeoutMs) {
  return withTimeout('R2 request timed out while reading Edge state', ({signal}) => s3.send(command, { abortSignal: signal }), timeoutMs);
}

async function priorEdge(s3, bucket, timeoutMs) {
  let pointer;
  try { pointer = await request(s3, new GetObjectCommand({ Bucket: bucket, Key: CHANNEL_KEY }), timeoutMs); }
  catch (error) { if (missing(error)) return undefined; if (error instanceof OperationTimeoutError) throw error; fail('Edge channel cannot be read'); }
  if (!pointer.Body || pointer.ContentType !== CHANNEL_TYPE || pointer.CacheControl !== 'public, max-age=30, must-revalidate') fail('Edge channel metadata differs');
  const channel = decodeChannel(await bytes(pointer.Body, MAX, timeoutMs));
  if (channel.channel !== 'edge') fail('Edge channel identity differs');
  const target = new URL(channel.manifest.url);
  const prefix = 'https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/';
  if (!channel.manifest.url.startsWith(prefix) || !target.pathname.endsWith('/manifest.json')) fail('Edge channel target is noncanonical');
  const key = target.pathname.slice(1);
  let remote;
  try { remote = await request(s3, new GetObjectCommand({ Bucket: bucket, Key: key }), timeoutMs); } catch (error) { if (error instanceof OperationTimeoutError) throw error; fail('Edge target manifest is unavailable'); }
  if (!remote.Body || remote.ContentType !== 'application/json' || remote.CacheControl !== IMMUTABLE_CACHE_CONTROL) fail('Edge target manifest metadata differs');
  const manifestBytes = await bytes(remote.Body, MAX, timeoutMs);
  if (manifestBytes.length !== channel.manifest.size || createHash('sha256').update(manifestBytes).digest('hex') !== channel.manifest.sha256) fail('Edge target manifest differs');
  try {
    const parsed = parseManifestBytes(manifestBytes);
    if (parsed.manifest.publication.type !== channel.target.type || parsed.manifest.publication.id !== channel.target.id || parsed.manifest.provenance.commit !== channel.target.id || parsed.layout.root !== key.slice(0, -'/manifest.json'.length)) fail('Edge channel target does not bind its manifest');
    return parsed;
  }
  catch (error) { if (/manifest validation failed/.test(error?.message ?? '')) fail('Edge target manifest is invalid'); throw error; }
}

async function copyExact(artifact, target) {
  const verified = await openVerifiedArtifact(artifact);
  let handle;
  try {
    handle = await open(target, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    let size = 0;
    for await (const part of verified.stream()) { const value = Buffer.from(part); size += value.length; if (size > verified.size) fail('raw artifact changed while materializing'); let offset = 0; while (offset < value.length) { const result = await handle.write(value, offset, value.length - offset, null); offset += result.bytesWritten; } }
    if (size !== verified.size) fail('raw artifact changed while materializing');
  } finally { await handle?.close().catch(()=>{}); await verified.close().catch(()=>{}); }
}

export async function prepareEdge({ manifestFile, releaseDirectory, outputDirectory, bucket, endpoint, accessKey, secretKey, s3Client, timeoutMs }) {
  if (!accessKey || !secretKey || !bucket) fail('R2 credentials are required');
  const output = resolve(outputDirectory);
  if (!isAbsolute(outputDirectory) || output !== outputDirectory) fail('output directory must be absent absolute normalized');
  await assertNoSymlinkComponents(dirname(output));
  try { await lstat(output); fail('output directory already exists'); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
  const raw = await loadRelease({ manifestFile, releaseDirectory });
  try {
    const rawView = assertReleaseArtifactContract(raw);
    if (rawView.manifest.publication.type !== 'build') fail('raw publication is not a build');
    const prior = await priorEdge(s3Client ?? client({ endpoint, accessKey, secretKey }), bucket, timeoutMs);
    const manifest = structuredClone(rawView.manifest);
    if (prior) for (const role of ['content', 'platform']) {
      const current = manifest.packs.find(pack => pack.role === role);
      const old = prior.manifest.packs.find(pack => pack.role === role);
      if (!old || current.sha1 !== old.sha1 || current.sha256 !== old.sha256 || current.size !== old.size) continue;
      current.url = old.url;
    }
    await mkdir(output, { recursive: false, mode: 0o700 });
    for (const artifact of rawView.artifacts.filter(value => value.role !== 'manifest')) await copyExact(artifact, join(output, artifact.name));
    await writeFile(join(output, 'manifest.json'), canonicalManifestText(manifest), { flag: 'wx', mode: 0o600 });
    const prepared = await loadRelease({ manifestFile: join(output, 'manifest.json'), releaseDirectory: output });
    await prepared.close();
    return { reused: prior ? manifest.packs.filter((pack, index) => pack.url !== raw.manifest.packs[index].url).length : 0 };
  } finally { await raw.close(); }
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest', '--release-directory', '--output-directory', '--bucket', '--endpoint', '--access-key', '--secret-key']);
  const result = await prepareEdge({ manifestFile: args['--manifest'], releaseDirectory: args['--release-directory'], outputDirectory: args['--output-directory'], bucket: args['--bucket'], endpoint: args['--endpoint'], accessKey: args['--access-key'], secretKey: args['--secret-key'] });
  return `prepared reused=${result.reused}`;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await runCli(main);
