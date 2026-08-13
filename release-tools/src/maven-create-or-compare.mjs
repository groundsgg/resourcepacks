#!/usr/bin/env node

import { createReadStream } from 'node:fs';
import { lstat, readdir, stat } from 'node:fs/promises';
import { join, relative, resolve, sep } from 'node:path';
import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';

import { runCli, strictArgs } from './cli.mjs';
import { digestStream, sameStreamBytes } from './digests.mjs';
import { readManifest } from './manifest.mjs';

async function collectFiles(root, directory = root, result = []) {
  for (const entry of await readdir(directory, {withFileTypes:true})) {
    const path = join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Maven staging contains a symbolic link: ${entry.name}`);
    if (entry.isDirectory()) await collectFiles(root,path,result);
    else if (entry.isFile()) result.push({file:path,path:relative(root,path).split(sep).join('/')});
    else throw new Error(`Maven staging contains a non-regular entry: ${entry.name}`);
  }
  return result;
}

export async function collectMavenPublication({stagingDirectory, manifest}) {
  const root = resolve(stagingDirectory);
  const rootStats = await lstat(root);
  if (!rootStats.isDirectory() || rootStats.isSymbolicLink()) throw new Error('Maven staging root must be a regular directory');
  const [group,artifact,version] = manifest.catalog.coordinate.split(':');
  if (group !== 'gg.grounds' || artifact !== 'resourcepacks-catalog' || version !== manifest.version) throw new Error('Maven coordinate does not match the manifest');
  const artifactRoot = `${group.replaceAll('.','/')}/${artifact}`;
  const versionRoot = `${artifactRoot}/${version}`;
  const files = (await collectFiles(root)).sort((left,right)=>left.path.localeCompare(right.path));
  if (!files.length) throw new Error('Maven staging directory is empty');
  const versionName = new RegExp(`^${artifact}-${version.replaceAll('.','\\.')}(?:-sources)?\\.(?:jar|pom|module)(?:\\.(?:md5|sha1|sha256|sha512))?$`);
  const metadataName = /^maven-metadata\.xml(?:\.(?:md5|sha1|sha256|sha512))?$/;
  for (const file of files) {
    const inVersion = file.path.startsWith(`${versionRoot}/`) && versionName.test(file.path.slice(versionRoot.length+1));
    const metadata = file.path.startsWith(`${artifactRoot}/`) && !file.path.slice(artifactRoot.length+1).includes('/') && metadataName.test(file.path.slice(artifactRoot.length+1));
    if (!inVersion && !metadata) throw new Error(`Maven staging file is outside the manifest coordinate: ${file.path}`);
    const stats = await stat(file.file);
    if (!stats.isFile()) throw new Error(`Maven staging entry is not a file: ${file.path}`);
    file.size = stats.size;
  }
  const mainJarPath = `${versionRoot}/${artifact}-${version}.jar`;
  const pomPath = `${versionRoot}/${artifact}-${version}.pom`;
  const mainJar = files.find(file=>file.path===mainJarPath);
  if (!mainJar || !files.some(file=>file.path===pomPath)) throw new Error('Maven staging must contain the manifest catalog JAR and POM');
  const jarDigest = await digestStream(createReadStream(mainJar.file), manifest.catalog.size);
  if (jarDigest.size !== manifest.catalog.size || jarDigest.sha256 !== manifest.catalog.sha256) throw new Error('Maven catalog JAR differs from the manifest');
  return files;
}

/** Checks an entire staged Maven publication before a caller invokes Gradle publish. */
export async function mavenCreateOrCompare({ repositoryUrl, directory, files, username, token, fetchImpl = fetch }) {
  const publicationFiles = files ?? (await readdir(directory)).sort().map(name=>({path:name,file:join(directory,name)}));
  if (!publicationFiles.length) throw new Error('Maven staging directory is empty');
  const base = new URL(`${repositoryUrl.replace(/\/$/,'')}/`);
  if (!['http:','https:'].includes(base.protocol) || base.username || base.password || base.protocol === 'http:' && !['127.0.0.1','localhost'].includes(base.hostname)) throw new Error('Maven repository URL is invalid or insecure');
  const headers = username === undefined && token === undefined ? {} : {Authorization:`Basic ${Buffer.from(`${username}:${token}`).toString('base64')}`};
  const results = [];
  for (const entry of publicationFiles) {
    const stats = await stat(entry.file);
    if (!stats.isFile()) throw new Error(`Maven staging entry is not a file: ${entry.path}`);
    let response;
    try { response = await fetchImpl(new URL(entry.path, base), {headers,redirect:'manual'}); } catch { throw new Error(`Maven comparison request failed for ${entry.path}`); }
    if (response.status === 404) results.push('missing');
    else if (response.status >= 300 && response.status < 400) throw new Error(`Maven comparison refused redirect for ${entry.path}`);
    else if (!response.ok || !response.body) throw new Error(`Maven comparison returned HTTP ${response.status} for ${entry.path}`);
    else results.push(await sameStreamBytes(createReadStream(entry.file), Readable.fromWeb(response.body), stats.size) ? 'same' : 'different');
  }
  if (results.every(result => result === 'missing')) return { publish: true };
  if (results.every(result => result === 'same')) return { publish: false };
  throw new Error('Maven publication is partial or differs; refusing publish');
}

async function main() {
  const args = strictArgs(process.argv.slice(2), ['--manifest','--staging-directory','--repository-url','--username','--token']);
  const {manifest} = await readManifest(args['--manifest']);
  const files = await collectMavenPublication({stagingDirectory:args['--staging-directory'],manifest});
  const result = await mavenCreateOrCompare({repositoryUrl:args['--repository-url'],files,username:args['--username'],token:args['--token']});
  return result.publish ? 'publish' : 'skip';
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await runCli(main);
