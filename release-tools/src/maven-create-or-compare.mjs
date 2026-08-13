#!/usr/bin/env node

import { createReadStream } from 'node:fs';
import { mkdir, mkdtemp, open, readdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';

import { runCli, strictArgs } from './cli.mjs';
import { sameStreamBytes } from './digests.mjs';
import { assertNoSymlinkComponents, openVerifiedArtifact, readManifest, snapshotRegularFile } from './manifest.mjs';
import { withTimeout } from './timeout.mjs';

export const MAVEN_REPOSITORY_URL='https://maven.pkg.github.com/groundsgg/resourcepacks/';
const MAX_MAVEN_FILE_SIZE=1024*1024*1024;

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

async function materializeSnapshotDirectory(files){
  const root=await mkdtemp(join(tmpdir(),'grounds-maven-publish-'));
  for(const file of files){
    const target=join(root,...file.path.split('/'));await mkdir(dirname(target),{recursive:true,mode:0o700});
    const output=await open(target,'wx',0o600);
    try{for await(const chunk of file.snapshot.stream()){let offset=0;const bytes=Buffer.from(chunk);while(offset<bytes.length){const{bytesWritten}=await output.write(bytes,offset,bytes.length-offset,null);offset+=bytesWritten;}}await output.sync();}finally{await output.close();}
    file.file=target;
  }
  return root;
}

export async function collectMavenPublication({stagingDirectory,manifest}){
  const root=resolve(stagingDirectory);await assertNoSymlinkComponents(root);
  const[group,artifact,version]=manifest.catalog.coordinate.split(':');
  if(group!=='gg.grounds'||artifact!=='resourcepacks-catalog'||version!==manifest.version)throw new Error('Maven coordinate does not match the manifest');
  const versionRoot=`${group.replaceAll('.','/')}/${artifact}/${version}`;
  const expectedNames=[`${artifact}-${version}.jar`,`${artifact}-${version}-sources.jar`,`${artifact}-${version}.pom`,`${artifact}-${version}.module`];
  let current=root;
  for(const component of ['gg','grounds',artifact,version]){await requireExactEntries(current,[component],'directory');current=join(current,component);await assertNoSymlinkComponents(current);}
  const entries=await requireExactEntries(current,expectedNames,'file');
  const found=entries.map(entry=>({file:join(current,entry.name),path:`${versionRoot}/${entry.name}`}));
  const snapshots=[];
  try{
    for(const file of found){await assertNoSymlinkComponents(dirname(file.file));const snapshot=await snapshotRegularFile(file.file,MAX_MAVEN_FILE_SIZE);snapshots.push(snapshot);file.snapshot=snapshot;file.size=snapshot.size;file.sha1=snapshot.sha1;file.sha256=snapshot.sha256;}
    const main=found.find(file=>file.path===`${versionRoot}/${artifact}-${version}.jar`);
    if(main.size!==manifest.catalog.size||main.sha256!==manifest.catalog.sha256)throw new Error('Maven catalog JAR differs from the manifest');
    const snapshotDirectory=await materializeSnapshotDirectory(found);
    return{files:found,snapshotDirectory,close:async()=>{await Promise.allSettled(snapshots.map(snapshot=>snapshot.close()));}};
  }catch(error){await Promise.allSettled(snapshots.map(snapshot=>snapshot.close()));throw error;}
}

export async function mavenCreateOrCompare({repositoryUrl=MAVEN_REPOSITORY_URL,directory,files,username,token,fetchImpl=fetch,allowLocalhostForTests=false,timeoutMs}){
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
      if(response.status===404){results.push('missing');return;}if(response.status>=300&&response.status<400)throw new Error(`Maven comparison refused redirect for ${entry.path}`);if(!response.ok||!response.body)throw new Error(`Maven comparison returned HTTP ${response.status} for ${entry.path}`);
      const local=verified?verified.stream():createReadStream(entry.file);const remote=Readable.fromWeb(response.body);onTimeout(()=>{local.destroy();remote.destroy();});results.push(await sameStreamBytes(local,remote,stats.size)?'same':'different');
    },timeoutMs);}finally{await verified?.close();}
  }
  if(results.every(result=>result==='missing'))return{publish:true};if(results.every(result=>result==='same'))return{publish:false};throw new Error('Maven publication is partial or differs; refusing publish');
}

async function main(){
  const args=strictArgs(process.argv.slice(2),['--manifest','--staging-directory','--username','--token']);const{manifest}=await readManifest(args['--manifest']);const publication=await collectMavenPublication({stagingDirectory:args['--staging-directory'],manifest});
  try{const result=await mavenCreateOrCompare({files:publication.files,username:args['--username'],token:args['--token']});return result.publish?`publish-directory=${publication.snapshotDirectory}`:'skip';}finally{await publication.close();}
}

if(process.argv[1]&&pathToFileURL(process.argv[1]).href===import.meta.url)await runCli(main);
