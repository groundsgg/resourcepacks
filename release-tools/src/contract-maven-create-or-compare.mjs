#!/usr/bin/env node

import { pathToFileURL } from 'node:url';

import { runCli, strictArgs } from './cli.mjs';
import { collectMavenPublication, mavenCreateOrCompare } from './maven-create-or-compare.mjs';

async function main(){
  const args=strictArgs(process.argv.slice(2),['--staging-directory','--version','--username','--token']);
  const coordinate=`gg.grounds:resourcepacks-contract:${args['--version']}`;
  const publication=await collectMavenPublication({stagingDirectory:args['--staging-directory'],coordinate});
  try{
    const result=await mavenCreateOrCompare({files:publication.files,stagingContract:publication.stagingContract,username:args['--username'],token:args['--token']});
    return result.publish?'publish':'skip';
  }finally{await publication.close();}
}

if(process.argv[1]&&pathToFileURL(process.argv[1]).href===import.meta.url)await runCli(main);
