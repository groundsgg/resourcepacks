#!/usr/bin/env node

import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import { digestStream } from './digests.mjs';
import { runCli, strictArgs } from './cli.mjs';
import { assertReleaseArtifactContract, loadRelease } from './manifest.mjs';
import { withTimeout } from './timeout.mjs';

const REQUIRED_CACHE_DIRECTIVES = new Map([['public', true], ['immutable', true], ['max-age', '31536000']]);

function cacheControlIsImmutable(value) {
  const parsed = (value ?? '').split(',').map(part => part.trim().toLowerCase()).filter(Boolean).map(part => {
    const [name, rawValue] = part.split('=', 2);
    return [name, rawValue?.replace(/^"|"$/g, '') ?? true];
  });
  const directives = new Map(parsed);
  return parsed.length === REQUIRED_CACHE_DIRECTIVES.size
    && directives.size === parsed.length
    && [...REQUIRED_CACHE_DIRECTIVES].every(([name, expected]) => directives.get(name) === expected);
}

async function fetchSameOrigin(url, fetchImpl, signal) {
  const original = new URL(url);
  let current = original;
  for (let count = 0; count < 4; count += 1) {
    let response;
    try { response = await fetchImpl(current, { redirect: 'manual',signal }); } catch(error) { if(signal.aborted)throw error;throw new Error(`CDN request failed for ${original.pathname}`); }
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get('location');
      if (!location) throw new Error(`CDN redirect missing location for ${original.pathname}`);
      const next = new URL(location, current);
      if (next.origin !== original.origin) throw new Error(`CDN redirected to another host for ${original.pathname}`);
      current = next;
      continue;
    }
    return response;
  }
  throw new Error(`CDN redirect limit exceeded for ${original.pathname}`);
}

export async function verifyCdn(release, {baseUrl, fetchImpl = fetch,timeoutMs} = {}) {
  const base = new URL(baseUrl ?? 'https://cdn.grounds.gg');
  if (!['http:','https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash || base.protocol === 'http:' && !['127.0.0.1','localhost'].includes(base.hostname)) throw new Error('CDN base URL is invalid or insecure');
  const {artifacts} = assertReleaseArtifactContract(release);
  for (const artifact of artifacts) {
    const canonical = new URL(`https://cdn.grounds.gg/${artifact.key}`);
    const requestUrl = new URL(canonical.pathname, `${base.origin}/`);
    await withTimeout(`CDN request timed out for ${canonical.pathname}`,async({signal,onTimeout})=>{
    const response = await fetchSameOrigin(requestUrl, fetchImpl,signal);
    if (!response.ok || !response.body) throw new Error(`CDN returned ${response.status} for ${canonical.pathname}`);
    if (response.headers.get('content-type')?.toLowerCase().split(';', 1)[0] !== artifact.contentType) {
      throw new Error(`CDN Content-Type mismatch for ${canonical.pathname}`);
    }
    if (!cacheControlIsImmutable(response.headers.get('cache-control'))) {
      throw new Error(`CDN Cache-Control mismatch for ${canonical.pathname}`);
    }
    const advertised = response.headers.get('content-length');
    if (advertised !== null && (!/^\d+$/.test(advertised) || Number(advertised) !== artifact.size)) throw new Error(`CDN Content-Length mismatch for ${canonical.pathname}`);
    const body=Readable.fromWeb(response.body);onTimeout(()=>body.destroy());
    const actual = await digestStream(body, artifact.size);
    if (actual.sha1 !== artifact.sha1 || actual.sha256 !== artifact.sha256 || actual.size !== artifact.size) {
      throw new Error(`CDN bytes mismatch for ${canonical.pathname}`);
    }
    },timeoutMs);
  }
  return {verified:artifacts.length};
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest','--release-directory','--base-url']);
  const release = await loadRelease({manifestFile:args['--manifest'],releaseDirectory:args['--release-directory']});
  try { const result = await verifyCdn(release,{baseUrl:args['--base-url']}); return `verified=${result.verified}`; }
  finally { await release.close(); }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
