#!/usr/bin/env node

import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import { GetObjectCommand, PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { NodeHttpHandler } from '@smithy/node-http-handler';
import { sameStreamBytes } from './digests.mjs';
import { CliUsageError, runCli, strictArgs } from './cli.mjs';
import { encodeChannel, decodeChannel } from './channel.mjs';
import { assertReleaseArtifactContract, loadRelease, openVerifiedArtifact } from './manifest.mjs';
import { DEFAULT_NETWORK_TIMEOUT_MS, OperationTimeoutError, withTimeout } from './timeout.mjs';
import { IMMUTABLE_CACHE_CONTROL } from './r2-create-or-compare.mjs';

export const CHANNEL_CACHE_CONTROL = 'public, max-age=30, must-revalidate';
const CHANNEL_CONTENT_TYPE = 'application/json; charset=utf-8';

function fail(detail) { throw new Error(`R2 channel advancement failed: ${detail}`); }
function endpointFor(endpoint) {
  let url; try { url = new URL(endpoint); } catch { throw new Error('R2 endpoint is invalid or insecure'); }
  if (!['https:','http:'].includes(url.protocol) || url.username || url.password || (url.protocol === 'http:' && !['127.0.0.1','localhost'].includes(url.hostname))) throw new Error('R2 endpoint is invalid or insecure');
  return url;
}
function missing(error) { return error?.name === 'NoSuchKey' || error?.$metadata?.httpStatusCode === 404; }
function precondition(error) { return error?.$metadata?.httpStatusCode === 412; }
function clientFor({endpoint,accessKey,secretKey,s3Client}) {
  return s3Client ?? new S3Client({endpoint:endpoint.href,region:'auto',forcePathStyle:true,maxAttempts:1,logger:{debug(){},info(){},warn(){},error(){}},requestHandler:new NodeHttpHandler({connectionTimeout:DEFAULT_NETWORK_TIMEOUT_MS,socketTimeout:DEFAULT_NETWORK_TIMEOUT_MS,requestTimeout:DEFAULT_NETWORK_TIMEOUT_MS,throwOnRequestTimeout:true}),credentials:{accessKeyId:accessKey,secretAccessKey:secretKey}});
}

async function exactManifest(s3, bucket, key, artifact, timeoutMs) {
  const verified = await openVerifiedArtifact(artifact);
  try {
    await withTimeout(`R2 request timed out while validating target ${key}`, async ({signal,onTimeout}) => {
      let remote;
      try { remote = await s3.send(new GetObjectCommand({Bucket:bucket,Key:key}), {abortSignal:signal}); } catch (error) { if (signal.aborted) throw error; fail('immutable target manifest is unavailable'); }
      if (!remote.Body || remote.ContentType !== 'application/json' || remote.CacheControl !== IMMUTABLE_CACHE_CONTROL) fail('immutable target manifest metadata differs');
      const local = verified.stream(); const body = Readable.from(remote.Body); onTimeout(() => { local.destroy(); body.destroy(); remote.Body.destroy?.(); });
      try { if (!await sameStreamBytes(local, body, verified.size)) fail('immutable target manifest differs'); }
      catch (error) { if (signal.aborted || /^R2 channel advancement failed:/.test(error?.message ?? '')) throw error; fail('immutable target manifest differs'); }
    }, timeoutMs);
  } finally { await verified.close().catch(()=>{}); }
}

async function currentChannel(s3, bucket, key, timeoutMs) {
  try {
    return await withTimeout(`R2 request timed out while reading channel ${key}`, async ({signal,onTimeout}) => {
      const current = await s3.send(new GetObjectCommand({Bucket:bucket,Key:key}), {abortSignal:signal});
      if (!current.Body || current.ContentType !== CHANNEL_CONTENT_TYPE || current.CacheControl !== CHANNEL_CACHE_CONTROL || typeof current.ETag !== 'string' || !/^"[0-9a-f]{32}(?:-[1-9][0-9]*)?"$/.test(current.ETag)) fail('existing channel metadata differs');
      const body = Readable.from(current.Body); const chunks=[]; let size=0; onTimeout(()=>{body.destroy();current.Body.destroy?.();});
      for await (const chunk of body) { const bytes=Buffer.from(chunk); size += bytes.length; if (size > 1024*1024) fail('existing channel exceeds parser limit'); chunks.push(bytes); }
      return {etag:current.ETag,bytes:Buffer.concat(chunks)};
    },timeoutMs);
  } catch (error) { if (missing(error)) return undefined; if (error instanceof OperationTimeoutError || /^R2 channel advancement failed:/.test(error?.message ?? '')) throw error; fail('channel read failed without exposing credentials'); }
}

export async function advanceChannel({channel,release,bucket,endpoint,accessKey,secretKey,sequence,s3Client,timeoutMs}) {
  if (!accessKey || !secretKey) throw new Error('R2 credentials are required');
  if (!bucket) throw new Error('R2 bucket is required');
  if (!Number.isSafeInteger(sequence) || sequence <= 0) throw new Error('channel sequence must be a positive safe integer');
  if (!['stable','edge'].includes(channel)) throw new Error('channel must be stable or edge');
  const endpointUrl=endpointFor(endpoint); const view=assertReleaseArtifactContract(release); const artifact=view.artifacts.find(value=>value.role==='manifest');
  if (!artifact) fail('release manifest contract missing');
  const publication=view.manifest.publication;
  if ((channel === 'stable' && publication.type !== 'release') || (channel === 'edge' && publication.type !== 'build')) throw new Error('channel target publication mismatch');
  const desired=encodeChannel({channel,sequence,publication,manifest:{sha256:artifact.sha256,size:artifact.size}}); const key=`resourcepacks/packsets/grounds-global/channels/${channel}.json`; const s3=clientFor({endpoint:endpointUrl,accessKey,secretKey,s3Client});
  await exactManifest(s3,bucket,artifact.key,artifact,timeoutMs);
  const current=await currentChannel(s3,bucket,key,timeoutMs);
  if (!current) {
    try { await withTimeout(`R2 request timed out while creating channel ${key}`,({signal})=>s3.send(new PutObjectCommand({Bucket:bucket,Key:key,Body:desired,ContentLength:desired.length,IfNoneMatch:'*',ContentType:CHANNEL_CONTENT_TYPE,CacheControl:CHANNEL_CACHE_CONTROL}),{abortSignal:signal}),timeoutMs); }
    catch (error) { if (error instanceof OperationTimeoutError) throw error; if (precondition(error)) fail('channel create raced; refusing overwrite'); fail('channel create failed without exposing credentials'); }
    return {decision:'created',channel,sequence};
  }
  const existing=decodeChannel(current.bytes);
  if (existing.channel !== channel) fail('existing channel pointer does not match requested channel');
  if (current.bytes.equals(desired)) return {decision:'unchanged',channel,sequence};
  if (sequence < existing.sequence) fail('channel sequence is stale');
  if (sequence === existing.sequence) fail('channel sequence conflicts with existing pointer');
  try { await withTimeout(`R2 request timed out while updating channel ${key}`,({signal})=>s3.send(new PutObjectCommand({Bucket:bucket,Key:key,Body:desired,ContentLength:desired.length,IfMatch:current.etag,ContentType:CHANNEL_CONTENT_TYPE,CacheControl:CHANNEL_CACHE_CONTROL}),{abortSignal:signal}),timeoutMs); }
  catch (error) { if (error instanceof OperationTimeoutError) throw error; if (precondition(error)) fail('channel update raced; refusing overwrite'); fail('channel update failed without exposing credentials'); }
  return {decision:'updated',channel,sequence};
}

async function main() {
  const args=strictArgs(process.argv.slice(2),['--channel','--manifest','--release-directory','--bucket','--endpoint','--access-key','--secret-key','--sequence']);
  const sequence=Number(args['--sequence']);
  if (!['stable','edge'].includes(args['--channel']) || !Number.isSafeInteger(sequence) || sequence <= 0) throw new CliUsageError('invalid command arguments');
  const release=await loadRelease({manifestFile:args['--manifest'],releaseDirectory:args['--release-directory']});
  try { const result=await advanceChannel({channel:args['--channel'],release,bucket:args['--bucket'],endpoint:args['--endpoint'],accessKey:args['--access-key'],secretKey:args['--secret-key'],sequence}); return `${result.decision} channel=${result.channel} sequence=${result.sequence}`; } finally { await release.close(); }
}
if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
