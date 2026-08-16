#!/usr/bin/env node

import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';

import { runCli, strictArgs } from './cli.mjs';
import { sameStreamBytes } from './digests.mjs';
import { assertReleaseArtifactContract, loadRelease, openVerifiedArtifact } from './manifest.mjs';
import { withTimeout } from './timeout.mjs';

const JSON_LIMIT = 1024 * 1024;

async function boundedJson(response, operation,onTimeout=()=>{}) {
  if (!response.ok || !response.body) throw new Error(`${operation} returned HTTP ${response.status}`);
  const chunks = []; let size = 0;
  const body=Readable.fromWeb(response.body);onTimeout(()=>body.destroy());
  for await (const chunk of body) {
    const bytes = Buffer.from(chunk); size += bytes.length;
    if (size > JSON_LIMIT) throw new Error(`${operation} response is too large`);
    chunks.push(bytes);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new Error(`${operation} returned invalid JSON`); }
}

export async function releaseAssetsCreateOrCompare({ release, existingAssets, upload, fetchImpl = fetch, downloadHeaders = {},timeoutMs }) {
  const {artifacts:assets} = assertReleaseArtifactContract(release);
  const names = assets.map(asset=>asset.name);
  if (assets.length !== 4 || new Set(names).size !== 4) throw new Error('GitHub Release requires exactly four expected assets');
  const expected = new Set(names);
  if (!Array.isArray(existingAssets) || existingAssets.some(asset=>typeof asset?.name!=='string' || !expected.has(asset.name)) || new Set(existingAssets.map(asset=>asset.name)).size !== existingAssets.length) throw new Error('GitHub Release contains unexpected or duplicate assets');
  let uploaded = 0; let identical = 0;
  for (const asset of assets) {
    const verified = await openVerifiedArtifact(asset);
    try {
      const existing = existingAssets.find(candidate => candidate.name === asset.name);
      if (!existing) { await upload(asset,verified); uploaded += 1; continue; }
      await withTimeout(`GitHub request timed out for ${asset.name}`,async({signal,onTimeout})=>{
      let response;
      try { response = await fetchImpl(existing.url, {headers:downloadHeaders,redirect:'manual',signal}); } catch(error) {if(signal.aborted)throw error;throw new Error(`GitHub asset download failed for ${asset.name}`); }
      if (response.status >= 300 && response.status < 400) {
        const location = response.headers.get('location');
        if (!location) throw new Error(`GitHub asset redirect is invalid for ${asset.name}`);
        const next = new URL(location, existing.url);
        if (next.protocol !== 'https:' && !(next.protocol === 'http:' && ['127.0.0.1','localhost'].includes(next.hostname))) throw new Error(`GitHub asset redirect is unsafe for ${asset.name}`);
        try { response = await fetchImpl(next, {redirect:'manual',signal}); } catch(error) {if(signal.aborted)throw error;throw new Error(`GitHub asset download failed for ${asset.name}`); }
      }
      if (!response.ok || !response.body) throw new Error(`GitHub asset download returned HTTP ${response.status} for ${asset.name}`);
      const local=verified.stream();const remote=Readable.fromWeb(response.body);onTimeout(()=>{local.destroy();remote.destroy();});
      if (!await sameStreamBytes(local,remote, verified.size)) throw new Error(`GitHub Release asset differs: ${asset.name}`);
      identical += 1;
      },timeoutMs);
    } finally { await verified.close().catch(()=>{}); }
  }
  return {uploaded,identical};
}

function apiHeaders(token, accept = 'application/vnd.github+json') {
  return {
    Accept:accept,
    Authorization:`Bearer ${token}`,
    'User-Agent':'grounds-resourcepacks-release-tools',
    'X-GitHub-Api-Version':'2022-11-28',
  };
}

export async function githubReleaseCreateOrCompare({release, apiBaseUrl = 'https://api.github.com', token, fetchImpl = fetch, allowLocalhostForTests = false,timeoutMs}) {
  const operation = assertReleaseArtifactContract(release);
  if (operation.manifest.publication.type !== 'release') throw new Error('GitHub Release assets require a release publication');
  const base = new URL(`${apiBaseUrl.replace(/\/$/,'')}/`);
  if (!['http:','https:'].includes(base.protocol) || base.username || base.password || base.protocol === 'http:' && !['127.0.0.1','localhost'].includes(base.hostname)) throw new Error('GitHub API base URL is invalid or insecure');
  const official = base.href === 'https://api.github.com/';
  const testLocalhost = allowLocalhostForTests && ['127.0.0.1','localhost'].includes(base.hostname);
  if (!official && !testLocalhost) throw new Error('GitHub API base URL is not trusted');
  const repository = operation.manifest.provenance.repository;
  const tag = operation.manifest.publication.id;
  const remoteRelease=await withTimeout('GitHub request timed out during release lookup',async({signal,onTimeout})=>{let response;try { response = await fetchImpl(new URL(`repos/${repository}/releases/tags/${encodeURIComponent(tag)}`,base),{headers:apiHeaders(token),redirect:'manual',signal}); } catch(error) {if(signal.aborted)throw error;throw new Error('GitHub release lookup failed'); }return boundedJson(response,'GitHub release lookup',onTimeout);},timeoutMs);
  if (!Number.isSafeInteger(remoteRelease.id) || remoteRelease.id <= 0 || typeof remoteRelease.upload_url !== 'string') throw new Error('GitHub release response is invalid');
  const listing=await withTimeout('GitHub request timed out during asset listing',async({signal,onTimeout})=>{let response;try { response = await fetchImpl(new URL(`repos/${repository}/releases/${remoteRelease.id}/assets?per_page=100`,base),{headers:apiHeaders(token),redirect:'manual',signal}); } catch(error) {if(signal.aborted)throw error;throw new Error('GitHub asset listing failed'); }if(response.headers.get('link')?.includes('rel="next"'))throw new Error('GitHub Release contains more than one page of assets');return boundedJson(response,'GitHub asset listing',onTimeout);},timeoutMs);
  const existingAssets=listing;
  if (!Array.isArray(existingAssets)) throw new Error('GitHub asset listing response is invalid');
  const uploadBase = remoteRelease.upload_url.replace('{?name,label}','');
  if (uploadBase === remoteRelease.upload_url) throw new Error('GitHub release upload URL is invalid');
  const uploadOrigin = new URL(uploadBase).origin;
  const officialUploadOrigin = base.hostname === 'api.github.com' ? 'https://uploads.github.com' : base.origin;
  if (uploadOrigin !== officialUploadOrigin) throw new Error('GitHub release upload URL is unsafe');
  for (const asset of existingAssets) {
    let assetUrl;
    try { assetUrl = new URL(asset.url); } catch { throw new Error('GitHub asset URL is invalid'); }
    if (assetUrl.origin !== base.origin) throw new Error('GitHub asset URL is unsafe');
  }
  const upload = async (asset,verified) => withTimeout(`GitHub request timed out for ${asset.name}`,async({signal,onTimeout})=>{
    const url = new URL(uploadBase); url.searchParams.set('name',asset.name);
    let result;
    try {
      const body=verified.stream();onTimeout(()=>body.destroy());result = await fetchImpl(url,{method:'POST',headers:{...apiHeaders(token),'Content-Type':'application/octet-stream','Content-Length':String(verified.size)},body,duplex:'half',redirect:'manual',signal});
    } catch(error) {if(signal.aborted)throw error;throw new Error(`GitHub asset upload failed for ${asset.name}`); }
    if (result.status !== 201) throw new Error(`GitHub asset upload returned HTTP ${result.status} for ${asset.name}`);
  },timeoutMs);
  return releaseAssetsCreateOrCompare({release,existingAssets,upload,fetchImpl,downloadHeaders:apiHeaders(token,'application/octet-stream'),timeoutMs});
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest','--release-directory','--token']);
  const release = await loadRelease({manifestFile:args['--manifest'],releaseDirectory:args['--release-directory']});
  try{const result = await githubReleaseCreateOrCompare({release,token:args['--token']});return `uploaded=${result.uploaded} identical=${result.identical}`;}finally{await release.close();}
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
