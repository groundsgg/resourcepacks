#!/usr/bin/env node

import { createReadStream } from 'node:fs';
import { lstat, readdir, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';

import { runCli, strictArgs } from './cli.mjs';
import { sameStreamBytes } from './digests.mjs';
import { assertNoSymlinkComponents, openVerifiedArtifact, readManifest, snapshotRegularFile } from './manifest.mjs';
import { withTimeout } from './timeout.mjs';

export const MAVEN_REPOSITORY_URL='https://maven.pkg.github.com/groundsgg/resourcepacks/';
const MAX_MAVEN_FILE_SIZE=1024*1024*1024;
const GITHUB_PACKAGE_STORAGE_HOST='pkg-containers.githubusercontent.com';

function trustedPackageRedirect(response,official){
  if(!official||response.status<300||response.status>=400)return null;
  const location=response.headers.get('location');
  if(!location)return null;
  const target=new URL(location);
  if(target.protocol!=='https:'||target.hostname!==GITHUB_PACKAGE_STORAGE_HOST||target.username||target.password||target.port)return null;
  return target;
}

async function requireExactEntries(directory,expectedNames,kind){
  const entries=(await readdir(directory,{withFileTypes:true})).sort((left,right)=>left.name.localeCompare(right.name));
  const expected=[...expectedNames].sort();
  for(const entry of entries){
    if(entry.isSymbolicLink())throw new Error(`Maven staging contains a symbolic link: ${entry.name}`);
    if(kind==='directory'&&!entry.isDirectory()||kind==='file'&&!entry.isFile())throw new Error(`Maven staging contains a non-regular ${kind}: ${entry.name}`);
  }
  if(entries.length!==expected.length||entries.some((entry,index)=>entry.name!==expected[index]))throw new Error('Maven staging must contain the exact Maven publication file set');
  return entries;
}

async function directoryIdentity(path){
  await assertNoSymlinkComponents(path);
  const stats=await lstat(path);
  if(stats.isSymbolicLink()||!stats.isDirectory())throw new Error('Maven staging hierarchy is not a regular directory');
  return{dev:stats.dev,ino:stats.ino};
}

async function validateStagingTree({root,components,expectedNames,identities}){
  let current=root;const actualIdentities=[await directoryIdentity(current)];
  for(const component of components){await requireExactEntries(current,[component],'directory');current=join(current,component);actualIdentities.push(await directoryIdentity(current));}
  await requireExactEntries(current,expectedNames,'file');
  for(const name of expectedNames){const path=join(current,name);await assertNoSymlinkComponents(path);const stats=await lstat(path);if(stats.isSymbolicLink()||!stats.isFile())throw new Error(`Maven staging contains a non-regular file: ${name}`);}
  if(identities&&actualIdentities.some((identity,index)=>identity.dev!==identities[index]?.dev||identity.ino!==identities[index]?.ino))throw new Error('Maven staging directory identity changed');
  return actualIdentities;
}

export async function collectMavenPublication({stagingDirectory,manifest}){
  const root=resolve(stagingDirectory);await assertNoSymlinkComponents(root);
  const[group,artifact,version]=manifest.catalog.coordinate.split(':');
  if(group!=='gg.grounds'||artifact!=='resourcepacks-catalog'||version!==manifest.version)throw new Error('Maven coordinate does not match the manifest');
  const versionRoot=`${group.replaceAll('.','/')}/${artifact}/${version}`;
  const expectedNames=[`${artifact}-${version}.jar`,`${artifact}-${version}-sources.jar`,`${artifact}-${version}.pom`,`${artifact}-${version}.module`];
  const components=['gg','grounds',artifact,version];
  const identities=await validateStagingTree({root,components,expectedNames});
  const current=join(root,...components);
  const entries=(await readdir(current,{withFileTypes:true})).sort((left,right)=>left.name.localeCompare(right.name));
  const found=entries.map(entry=>({file:join(current,entry.name),path:`${versionRoot}/${entry.name}`}));
  const snapshots=[];
  try{
    for(const file of found){await assertNoSymlinkComponents(dirname(file.file));const snapshot=await snapshotRegularFile(file.file,MAX_MAVEN_FILE_SIZE);snapshots.push(snapshot);file.snapshot=snapshot;file.size=snapshot.size;file.sha1=snapshot.sha1;file.sha256=snapshot.sha256;}
    const main=found.find(file=>file.path===`${versionRoot}/${artifact}-${version}.jar`);
    if(main.size!==manifest.catalog.size||main.sha256!==manifest.catalog.sha256)throw new Error('Maven catalog JAR differs from the manifest');
    return{files:found,stagingContract:Object.freeze({root,components:Object.freeze(components),expectedNames:Object.freeze(expectedNames),identities:Object.freeze(identities.map(Object.freeze))}),close:async()=>{await Promise.allSettled(snapshots.map(snapshot=>snapshot.close()));}};
  }catch(error){await Promise.allSettled(snapshots.map(snapshot=>snapshot.close()));throw error;}
}

async function assertPublicationUnchanged(files){
  for(const entry of files.filter(file=>file.snapshot)){
    let current;
    try{
      current=await snapshotRegularFile(entry.file,MAX_MAVEN_FILE_SIZE);
      if(current.size!==entry.size||current.sha1!==entry.sha1||current.sha256!==entry.sha256)throw new Error('changed');
    }catch{throw new Error(`Maven staging changed during comparison: ${entry.path}`);}
    finally{await current?.close().catch(()=>{});}
  }
}

async function assertStagingTreeUnchanged(contract){
  if(!contract)return;
  try{await validateStagingTree(contract);}catch{throw new Error('Maven staging changed during comparison');}
}

export async function mavenCreateOrCompare({repositoryUrl=MAVEN_REPOSITORY_URL,directory,files,stagingContract,username,token,fetchImpl=fetch,allowLocalhostForTests=false,timeoutMs}){
  const publicationFiles=files??(await readdir(directory)).sort().map(name=>({path:name,file:join(directory,name)}));
  if(!publicationFiles.length)throw new Error('Maven staging directory is empty');
  const base=new URL(`${repositoryUrl.replace(/\/$/,'')}/`);
  const official=base.href===MAVEN_REPOSITORY_URL;const local=allowLocalhostForTests&&['127.0.0.1','localhost'].includes(base.hostname);
  if(!official&&!local)throw new Error('Maven repository URL is not trusted');
  const headers=username===undefined&&token===undefined?{}:{Authorization:`Basic ${Buffer.from(`${username}:${token}`).toString('base64')}`};const results=[];
  for(const entry of publicationFiles){
    const verified=entry.snapshot?await openVerifiedArtifact(entry):null;const stats=verified?{size:verified.size}:await stat(entry.file);
    try{if(!verified&&!stats.isFile())throw new Error(`Maven staging entry is not a file: ${entry.path}`);
    await withTimeout(`Maven request timed out for ${entry.path}`,async({signal,onTimeout})=>{
      let response;try{response=await fetchImpl(new URL(entry.path,base),{headers,redirect:'manual',signal});}catch(error){if(signal.aborted)throw error;throw new Error(`Maven comparison request failed for ${entry.path}`);}
      if(response.status===404){results.push('missing');return;}if(response.status>=300&&response.status<400){const target=trustedPackageRedirect(response,official);if(!target)throw new Error(`Maven comparison refused redirect for ${entry.path}`);await response.body?.cancel();try{response=await fetchImpl(target,{headers:{},redirect:'manual',signal});}catch(error){if(signal.aborted)throw error;throw new Error(`Maven comparison request failed for ${entry.path}`);}if(response.status>=300&&response.status<400)throw new Error(`Maven comparison refused redirect for ${entry.path}`);}if(!response.ok||!response.body)throw new Error(`Maven comparison returned HTTP ${response.status} for ${entry.path}`);
      const local=verified?verified.stream():createReadStream(entry.file);const remote=Readable.fromWeb(response.body);onTimeout(()=>{local.destroy();remote.destroy();});results.push(await sameStreamBytes(local,remote,stats.size)?'same':'different');
    },timeoutMs);}finally{await verified?.close();}
  }
  await assertPublicationUnchanged(publicationFiles);
  await assertStagingTreeUnchanged(stagingContract);
  if(results.every(result=>result==='missing'))return{publish:true};if(results.every(result=>result==='same'))return{publish:false};throw new Error('Maven publication is partial or differs; refusing publish');
}

async function main(){
  const args=strictArgs(process.argv.slice(2),['--manifest','--staging-directory','--username','--token']);const{manifest}=await readManifest(args['--manifest']);const publication=await collectMavenPublication({stagingDirectory:args['--staging-directory'],manifest});
  try{const result=await mavenCreateOrCompare({files:publication.files,stagingContract:publication.stagingContract,username:args['--username'],token:args['--token']});return result.publish?'publish':'skip';}finally{await publication.close();}
}

if(process.argv[1]&&pathToFileURL(process.argv[1]).href===import.meta.url)await runCli(main);
