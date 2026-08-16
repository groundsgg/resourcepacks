#!/usr/bin/env node

import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import { GetObjectCommand, PutObjectCommand, S3Client, S3ServiceException } from '@aws-sdk/client-s3';
import { NodeHttpHandler } from '@smithy/node-http-handler';
import { sameStreamBytes } from './digests.mjs';
import { runCli, strictArgs } from './cli.mjs';
import { assertReleaseArtifactContract, loadRelease, openVerifiedArtifact } from './manifest.mjs';
import { DEFAULT_NETWORK_TIMEOUT_MS, OperationTimeoutError, withTimeout } from './timeout.mjs';

export const IMMUTABLE_CACHE_CONTROL = 'public, max-age=31536000, immutable';

/** Creates an R2 object exactly once, accepting only a byte-identical existing object. */
export async function createOrCompare({ endpoint, bucket, key, file, artifact, accessKey, secretKey, expected, contentType = 'application/zip', client, timeoutMs }) {
  if (!accessKey || !secretKey) throw new Error('R2 credentials are required');
  const endpointUrl = new URL(endpoint);
  if (!['https:','http:'].includes(endpointUrl.protocol) || endpointUrl.username || endpointUrl.password || endpointUrl.protocol === 'http:' && !['127.0.0.1','localhost'].includes(endpointUrl.hostname)) throw new Error('R2 endpoint is invalid or insecure');
  const verified = await openVerifiedArtifact(artifact ?? {path:file,sha1:expected?.sha1,sha256:expected?.sha256,size:expected?.size});
  const quietLogger = {debug(){},info(){},warn(){},error(){}};
  const s3 = client ?? new S3Client({
    endpoint:endpointUrl.href,
    region:'auto',
    forcePathStyle:true,
    maxAttempts:1,
    logger:quietLogger,
    requestHandler:new NodeHttpHandler({
      connectionTimeout:DEFAULT_NETWORK_TIMEOUT_MS,
      socketTimeout:DEFAULT_NETWORK_TIMEOUT_MS,
      requestTimeout:DEFAULT_NETWORK_TIMEOUT_MS,
      throwOnRequestTimeout:true,
    }),
    credentials:{accessKeyId:accessKey,secretAccessKey:secretKey},
  });
  try {
    let put;
    try {
      put = await withTimeout(`R2 request timed out during upload for ${key}`,async({signal,onTimeout})=>{
        const body=verified.stream();onTimeout(()=>body.destroy());
        return s3.send(new PutObjectCommand({Bucket:bucket,Key:key,Body:body,ContentLength:verified.size,IfNoneMatch:'*',ContentType:contentType,CacheControl:IMMUTABLE_CACHE_CONTROL}),{abortSignal:signal});
      },timeoutMs);
    } catch (error) {
      if(error instanceof OperationTimeoutError)throw error;
      if (!(error instanceof S3ServiceException) || error.$metadata?.httpStatusCode !== 412) throw new Error('R2 upload failed without exposing credentials');
    }
    if (put) return { created: true };
    await withTimeout(`R2 request timed out during comparison for ${key}`,async({signal,onTimeout})=>{
      let existing;
      try{existing=await s3.send(new GetObjectCommand({Bucket:bucket,Key:key}),{abortSignal:signal});}
      catch(error){if(signal.aborted)throw error;throw new Error('R2 comparison download failed without exposing credentials');}
      if(!existing.Body)throw new Error('R2 comparison download did not return a body');
      if(existing.ContentType!==contentType||existing.CacheControl!==IMMUTABLE_CACHE_CONTROL)throw new Error('R2 existing object metadata differs; refusing overwrite');
      const local=verified.stream();const remote=Readable.from(existing.Body);onTimeout(()=>{local.destroy();remote.destroy();existing.Body.destroy?.();});
      if(!await sameStreamBytes(local,remote,verified.size))throw new Error('R2 existing object differs; refusing overwrite');
    },timeoutMs);
    return { created: false };
  } finally { await verified.close().catch(()=>{}); }
}

export async function r2ReleaseCreateOrCompare({release, endpoint, bucket, accessKey, secretKey, timeoutMs}) {
  const {artifacts} = assertReleaseArtifactContract(release);
  let created = 0;
  for (const artifact of artifacts) {
    const result = await createOrCompare({endpoint,bucket,key:artifact.key,artifact,accessKey,secretKey,expected:artifact,contentType:artifact.contentType,timeoutMs});
    if (result.created) created += 1;
  }
  return {created,identical:artifacts.length-created};
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest','--release-directory','--bucket','--endpoint','--access-key','--secret-key']);
  const release = await loadRelease({manifestFile:args['--manifest'],releaseDirectory:args['--release-directory']});
  try{
    const result = await r2ReleaseCreateOrCompare({release,endpoint:args['--endpoint'],bucket:args['--bucket'],accessKey:args['--access-key'],secretKey:args['--secret-key']});
    return `created=${result.created} identical=${result.identical}`;
  }finally{await release.close();}
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
