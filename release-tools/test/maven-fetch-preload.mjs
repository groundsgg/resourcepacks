import { appendFileSync } from 'node:fs';

globalThis.fetch=async(input,options={})=>{
  const url=new URL(input);
  if(url.origin!=='https://maven.pkg.github.com')return new Response('',{status:500});
  appendFileSync(process.env.MAVEN_FAKE_LOG,`${url.pathname}\n`);
  return new Response('',{status:404});
};
