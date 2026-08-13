import { appendFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

globalThis.fetch=async(input,options={})=>{
  const url=new URL(input);
  if(url.origin!=='https://maven.pkg.github.com')return new Response('',{status:500});
  appendFileSync(process.env.MAVEN_FAKE_LOG,`${url.pathname}\n`);
  if(process.env.MAVEN_FAKE_MODE==='error')return new Response('',{status:503});
  if(process.env.MAVEN_FAKE_MODE==='same'){
    const relative=url.pathname.replace(/^\/groundsgg\/resourcepacks\//,'');
    return new Response(readFileSync(join(process.env.MAVEN_FAKE_ROOT,...relative.split('/'))),{status:200});
  }
  return new Response('',{status:404});
};
