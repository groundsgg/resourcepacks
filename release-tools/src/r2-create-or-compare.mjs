#!/usr/bin/env node

import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import { GetObjectCommand, PutObjectCommand, S3Client, S3ServiceException } from '@aws-sdk/client-s3';
import { sameStreamBytes } from './digests.mjs';
import { runCli, strictArgs } from './cli.mjs';
import { loadRelease, openVerifiedArtifact } from './manifest.mjs';

export const IMMUTABLE_CACHE_CONTROL = 'public, max-age=31536000, immutable';

/** Creates an R2 object exactly once, accepting only a byte-identical existing object. */
export async function createOrCompare({ endpoint, bucket, key, file, accessKey, secretKey, expected, client }) {
  if (!accessKey || !secretKey) throw new Error('R2 credentials are required');
  const endpointUrl = new URL(endpoint);
  if (!['https:','http:'].includes(endpointUrl.protocol) || endpointUrl.username || endpointUrl.password || endpointUrl.protocol === 'http:' && !['127.0.0.1','localhost'].includes(endpointUrl.hostname)) throw new Error('R2 endpoint is invalid or insecure');
  const verified = await openVerifiedArtifact({path:file,sha1:expected?.sha1,sha256:expected?.sha256,size:expected?.size});
  const quietLogger = {debug(){},info(){},warn(){},error(){}};
  const s3 = client ?? new S3Client({ endpoint:endpointUrl.href, region: 'auto', forcePathStyle: true, maxAttempts:1, logger:quietLogger, credentials: { accessKeyId: accessKey, secretAccessKey: secretKey } });
  try {
    let put;
    const body = verified.stream();
    try {
      put = await s3.send(new PutObjectCommand({ Bucket: bucket, Key: key, Body: body, ContentLength: verified.size, IfNoneMatch: '*', ContentType: 'application/zip', CacheControl: IMMUTABLE_CACHE_CONTROL }));
    } catch (error) {
      if (!(error instanceof S3ServiceException) || error.$metadata?.httpStatusCode !== 412) throw new Error('R2 upload failed without exposing credentials');
    }
    if (put) return { created: true };
    let existing;
    try { existing = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: key })); } catch { throw new Error('R2 comparison download failed without exposing credentials'); }
    if (!existing.Body) throw new Error('R2 comparison download did not return a body');
    if (existing.ContentType !== 'application/zip' || existing.CacheControl !== IMMUTABLE_CACHE_CONTROL) throw new Error('R2 existing object metadata differs; refusing overwrite');
    const equal = await sameStreamBytes(verified.stream(), Readable.from(existing.Body), verified.size);
    if (!equal) throw new Error('R2 existing object differs; refusing overwrite');
    return { created: false };
  } finally { await verified.close().catch(()=>{}); }
}

export async function r2ReleaseCreateOrCompare({release, endpoint, bucket, accessKey, secretKey}) {
  let created = 0;
  for (const pack of release.packs) {
    const result = await createOrCompare({endpoint,bucket,key:pack.key,file:pack.artifact.path,accessKey,secretKey,expected:pack});
    if (result.created) created += 1;
  }
  return {created,identical:release.packs.length-created};
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest','--release-directory','--bucket','--endpoint','--access-key','--secret-key']);
  const release = await loadRelease({manifestFile:args['--manifest'],releaseDirectory:args['--release-directory']});
  const result = await r2ReleaseCreateOrCompare({release,endpoint:args['--endpoint'],bucket:args['--bucket'],accessKey:args['--access-key'],secretKey:args['--secret-key']});
  return `created=${result.created} identical=${result.identical}`;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
