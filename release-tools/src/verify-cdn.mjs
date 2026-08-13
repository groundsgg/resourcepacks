#!/usr/bin/env node

import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import { digestStream } from './digests.mjs';
import { runCli, strictArgs } from './cli.mjs';
import { readManifest } from './manifest.mjs';

const REQUIRED_CACHE_DIRECTIVES = new Map([['public', true], ['immutable', true], ['max-age', '31536000']]);

function cacheControlIsImmutable(value) {
  const directives = new Map((value ?? '').split(',').map(part => part.trim().toLowerCase()).filter(Boolean).map(part => {
    const [name, rawValue] = part.split('=', 2);
    return [name, rawValue?.replace(/^"|"$/g, '') ?? true];
  }));
  return [...REQUIRED_CACHE_DIRECTIVES].every(([name, expected]) => directives.get(name) === expected);
}

async function fetchSameOrigin(url, fetchImpl) {
  const original = new URL(url);
  let current = original;
  for (let count = 0; count < 4; count += 1) {
    let response;
    try { response = await fetchImpl(current, { redirect: 'manual' }); } catch { throw new Error(`CDN request failed for ${original.pathname}`); }
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

export async function verifyCdn(manifest, {baseUrl, fetchImpl = fetch} = {}) {
  const base = new URL(baseUrl ?? 'https://cdn.grounds.gg');
  if (!['http:','https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash || base.protocol === 'http:' && !['127.0.0.1','localhost'].includes(base.hostname)) throw new Error('CDN base URL is invalid or insecure');
  for (const pack of manifest.packs) {
    const canonical = new URL(pack.url);
    const requestUrl = new URL(canonical.pathname, `${base.origin}/`);
    const response = await fetchSameOrigin(requestUrl, fetchImpl);
    if (!response.ok || !response.body) throw new Error(`CDN returned ${response.status} for ${new URL(pack.url).pathname}`);
    if (response.headers.get('content-type')?.toLowerCase().split(';', 1)[0] !== 'application/zip') {
      throw new Error(`CDN Content-Type mismatch for ${new URL(pack.url).pathname}`);
    }
    if (!cacheControlIsImmutable(response.headers.get('cache-control'))) {
      throw new Error(`CDN Cache-Control mismatch for ${new URL(pack.url).pathname}`);
    }
    const advertised = response.headers.get('content-length');
    if (advertised !== null && (!/^\d+$/.test(advertised) || Number(advertised) !== pack.size)) throw new Error(`CDN Content-Length mismatch for ${canonical.pathname}`);
    const actual = await digestStream(Readable.fromWeb(response.body), pack.size);
    if (actual.sha1 !== pack.sha1 || actual.sha256 !== pack.sha256 || actual.size !== pack.size) {
      throw new Error(`CDN bytes mismatch for ${new URL(pack.url).pathname}`);
    }
  }
  return {verified:manifest.packs.length};
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest','--base-url']);
  const {manifest} = await readManifest(args['--manifest']);
  const result = await verifyCdn(manifest,{baseUrl:args['--base-url']});
  return `verified=${result.verified}`;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
