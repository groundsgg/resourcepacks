import { appendFileSync } from 'node:fs';

globalThis.fetch=async(input,options={})=>{
  const url=new URL(input);
  if(url.href==='https://api.github.com/repos/groundsgg/resourcepacks/releases/tags/v0.1.0'){
    return new Response(JSON.stringify({id:7,upload_url:'https://uploads.github.com/repos/groundsgg/resourcepacks/releases/7/assets{?name,label}'}),{status:200,headers:{'Content-Type':'application/json'}});
  }
  if(url.href==='https://api.github.com/repos/groundsgg/resourcepacks/releases/7/assets?per_page=100'){
    return new Response('[]',{status:200,headers:{'Content-Type':'application/json'}});
  }
  if(options.method==='POST'&&url.origin==='https://uploads.github.com'){
    for await(const _chunk of options.body){/* consume verified snapshot */}
    appendFileSync(process.env.GITHUB_FAKE_LOG,`${url.searchParams.get('name')}\n`);
    return new Response('{}',{status:201,headers:{'Content-Type':'application/json'}});
  }
  return new Response('',{status:404});
};
