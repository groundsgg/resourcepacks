import { createHash, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, open, readdir, realpath, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, parse, relative, resolve, sep } from 'node:path';
import { TextDecoder } from 'node:util';

import { digestStream } from './digests.mjs';

const CONTENT_UUID = '44591d5b-71f5-5c2a-a5b2-d3ee7be47e53';
const PLATFORM_UUID = '8da7cffe-bb04-55e0-9868-7789ce5de362';
const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;
const HEX40 = /^[0-9a-f]{40}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const MAX_MANIFEST_SIZE = 1024 * 1024;
const CONTENT_SIZE_LIMIT = 128 * 1024 * 1024;
const PLATFORM_SIZE_LIMIT = 16 * 1024 * 1024;
const CATALOG_SIZE_LIMIT = 1024 * 1024 * 1024;
const RELEASE_CONTRACTS = new WeakMap();
const PRIVATE_ARTIFACT_SNAPSHOTS = new WeakMap();
const SNAPSHOT_RECORDS = new WeakMap();

function fail(detail) {
  throw new Error(`manifest validation failed: ${detail}`);
}

function exactKeys(value, expected, pointer) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail(`${pointer} must be an object`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) fail(`${pointer} has unexpected fields`);
}

function positiveInteger(value, pointer) {
  if (!Number.isSafeInteger(value) || value <= 0) fail(`${pointer} must be a positive safe integer`);
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonicalize(value[key])]));
  }
  return value;
}

function deepFreeze(value) {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}

export function canonicalManifestText(value) {
  return `${JSON.stringify(canonicalize(value), null, 2)}\n`;
}

function publicationLayout(manifest) {
  exactKeys(manifest.publication, ['id','type'], '/publication');
  if (!['release','build'].includes(manifest.publication.type)) fail('publication type mismatch');
  if (!HEX40.test(manifest.provenance.commit)) fail('provenance mismatch');
  const {type,id} = manifest.publication;
  if (type === 'release' && id !== `v${manifest.version}`) fail('release publication mismatch');
  if (type === 'build' && (id !== manifest.provenance.commit || !new RegExp(`^0\\.0\\.0-edge\\.[1-9][0-9]*\\.g${id.slice(0,12)}$`).test(manifest.version))) fail('build publication mismatch');
  const root = type === 'release'
    ? `resourcepacks/packsets/grounds-global/releases/${id}`
    : `resourcepacks/packsets/grounds-global/builds/${manifest.provenance.commit}`;
  const suffix = type === 'release' ? id : `edge-${manifest.provenance.commit.slice(0, 12)}`;
  return {type,id,root,suffix};
}

function validatePack(pack, expected, layout) {
  exactKeys(pack, ['id','order','required','resourcePackFormat','role','sha1','sha256','size','url','uuid'], `/packs/${expected.order}`);
  if (pack.order !== expected.order || pack.role !== expected.role || pack.id !== `grounds-${expected.role}` || pack.uuid !== expected.uuid) fail(`pack ${expected.role} identity mismatch`);
  if (pack.required !== true || pack.resourcePackFormat !== 88) fail(`pack ${expected.role} policy mismatch`);
  if (!HEX40.test(pack.sha1) || !HEX64.test(pack.sha256)) fail(`pack ${expected.role} digest mismatch`);
  positiveInteger(pack.size, `/packs/${expected.order}/size`);
  if (pack.size > expected.maximumSize) fail(`pack ${expected.role} size exceeds product limit`);
  const file = `grounds-${expected.role}-pack-${layout.suffix}.zip`;
  const expectedUrl = `https://cdn.grounds.gg/${layout.root}/${file}`;
  if (pack.url !== expectedUrl) fail(`pack ${expected.role} URL mismatch`);
  return {
    ...pack,
    file,
    key: layout.root + `/${file}`,
  };
}

export function validateManifest(manifest) {
  exactKeys(manifest, ['catalog','minecraft','packSet','packs','provenance','publication','schemaVersion','version'], '/');
  if (manifest.schemaVersion !== 2 || manifest.packSet !== 'grounds-global' || typeof manifest.version !== 'string' || !SEMVER.test(manifest.version)) fail('root identity mismatch');
  exactKeys(manifest.minecraft, ['resourcePackFormat','version'], '/minecraft');
  if (manifest.minecraft.version !== '26.2' || manifest.minecraft.resourcePackFormat !== 88) fail('Minecraft target mismatch');
  exactKeys(manifest.catalog, ['coordinate','file','id','sha256','size','version'], '/catalog');
  exactKeys(manifest.provenance, ['commit','repository'], '/provenance');
  if (manifest.provenance.repository !== 'groundsgg/resourcepacks') fail('provenance mismatch');
  const layout = publicationLayout(manifest);
  const catalogFile = `grounds-resourcepack-catalog-${layout.suffix}.jar`;
  if (manifest.catalog.id !== 'grounds:resourcepacks' || manifest.catalog.version !== manifest.version || manifest.catalog.coordinate !== `gg.grounds:resourcepacks-catalog:${manifest.version}` || manifest.catalog.file !== catalogFile || !HEX64.test(manifest.catalog.sha256)) fail('catalog identity mismatch');
  positiveInteger(manifest.catalog.size, '/catalog/size');
  if (manifest.catalog.size > CATALOG_SIZE_LIMIT) fail('catalog size exceeds product limit');
  if (!Array.isArray(manifest.packs) || manifest.packs.length !== 2) fail('packs must contain exactly content and platform');
  const packs = [
    validatePack(manifest.packs[0], {order:0,role:'content',uuid:CONTENT_UUID,maximumSize:CONTENT_SIZE_LIMIT}, layout),
    validatePack(manifest.packs[1], {order:1,role:'platform',uuid:PLATFORM_UUID,maximumSize:PLATFORM_SIZE_LIMIT}, layout),
  ];
  return { manifest, packs, catalogFile, layout };
}

export function assertReleaseArtifactContract(release) {
  const contract = release && typeof release === 'object' ? RELEASE_CONTRACTS.get(release) : undefined;
  if (!contract || release.manifest !== contract.publicManifest || release.layout !== contract.publicLayout || release.directory !== contract.directory || release.artifacts !== contract.publicArtifacts || release.packs !== contract.publicPacks || release.catalog !== contract.publicCatalog || release.manifestArtifact !== contract.publicManifestArtifact || !Buffer.from(canonicalManifestText(release.manifest)).equals(contract.manifestBytes)) throw new Error('release artifact contract mismatch');
  if (release.layout.root !== contract.layout.root || release.layout.type !== contract.layout.type || release.layout.id !== contract.layout.id || release.packs.length !== 2 || release.packs[0] !== contract.publicPacks[0] || release.packs[1] !== contract.publicPacks[1] || release.catalog !== contract.publicCatalog || release.manifestArtifact !== contract.publicManifestArtifact) throw new Error('release artifact contract mismatch');
  for (let index=0; index<contract.artifacts.length; index+=1) {
    const publicArtifact=release.artifacts[index];const artifact=contract.artifacts[index];
    if (publicArtifact !== contract.publicArtifacts[index] || !publicArtifact || ['name','path','role','key','contentType','size','sha1','sha256'].some(field=>publicArtifact[field]!==artifact[field])) throw new Error('release artifact contract mismatch');
  }
  return contract.view;
}

async function readNoFollow(path) {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const stats = await handle.stat();
    if (!stats.isFile()) throw new Error('not regular');
    if (stats.size > MAX_MANIFEST_SIZE) throw new Error('too large');
    return await handle.readFile();
  } catch (error) {
    if (error instanceof Error && error.message === 'too large') throw new Error('manifest is too large');
    throw new Error('manifest cannot be read as a regular no-follow file');
  } finally {
    await handle?.close().catch(() => {});
  }
}

export async function readManifest(file) {
  await assertNoSymlinkComponents(dirname(resolve(file)));
  const bytes = await readNoFollow(file);
  return parseManifestBytes(bytes);
}

function parseManifestBytes(bytes) {
  let text;
  try { text = new TextDecoder('utf-8', {fatal:true}).decode(bytes); } catch { fail('manifest is not UTF-8'); }
  let manifest;
  try { manifest = JSON.parse(text); } catch { fail('manifest is not valid JSON'); }
  if (canonicalManifestText(manifest) !== text) fail('manifest is not canonical JSON');
  return validateManifest(manifest);
}

export async function assertNoSymlinkComponents(path) {
  const absolute = resolve(path);
  const root = parse(absolute).root;
  let current = root;
  for (const component of absolute.slice(root.length).split(sep).filter(Boolean)) {
    current = join(current, component);
    const stats = await lstat(current);
    if (stats.isSymbolicLink()) throw new Error(`release path contains a symbolic link: ${component}`);
  }
}

async function digestRegularFile(path, maximumSize) {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const stats = await handle.stat();
    if (!stats.isFile()) throw new Error('not regular');
    const digest = await digestStream(handle.createReadStream({autoClose:false}), maximumSize);
    return { ...digest, stats };
  } finally {
    await handle?.close().catch(() => {});
  }
}

export async function openVerifiedArtifact(artifact) {
  const privateSnapshot=PRIVATE_ARTIFACT_SNAPSHOTS.get(artifact);
  const snapshot=privateSnapshot ?? SNAPSHOT_RECORDS.get(artifact?.snapshot);
  if(snapshot){
    if(snapshot.size!==artifact.size||snapshot.sha1!==artifact.sha1||snapshot.sha256!==artifact.sha256)throw new Error('artifact snapshot contract mismatch');
    return {size:snapshot.size,stream:()=>snapshot.handle.createReadStream({start:0,autoClose:false}),close:async()=>{}};
  }
  const path = artifact.path ?? artifact.file;
  return snapshotPath(path,artifact,artifact.size);
}

async function snapshotPath(path,expected,maximumSize,captureBytes=false,tooLargeMessage='artifact does not match manifest') {
  let source; let snapshot;
  try {
    await assertNoSymlinkComponents(dirname(resolve(path)));
    source = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const stats = await source.stat();
    if (!stats.isFile()) throw new Error('not regular');
    if (!Number.isSafeInteger(maximumSize) || maximumSize <= 0 || maximumSize > CATALOG_SIZE_LIMIT) throw new Error('missing artifact contract');
    const snapshotPath = join(tmpdir(),`grounds-release-${process.pid}-${randomUUID()}.snapshot`);
    snapshot = await open(snapshotPath,constants.O_CREAT|constants.O_EXCL|constants.O_RDWR,0o600);
    await unlink(snapshotPath);
    const sha1=createHash('sha1');const sha256=createHash('sha256');let size=0;const captured=[];
    for await(const chunk of source.createReadStream({start:0,autoClose:false})){
      const bytes=Buffer.from(chunk);size+=bytes.length;
      if(size>maximumSize)throw new Error(tooLargeMessage);
      sha1.update(bytes);sha256.update(bytes);
      if(captureBytes)captured.push(bytes);
      let offset=0;while(offset<bytes.length){const {bytesWritten}=await snapshot.write(bytes,offset,bytes.length-offset,null);offset+=bytesWritten;}
    }
    const actual={size,sha1:sha1.digest('hex'),sha256:sha256.digest('hex')};
    if (expected && (actual.size !== expected.size || actual.sha1 !== expected.sha1 || actual.sha256 !== expected.sha256)) throw new Error('artifact does not match manifest');
    await source.close();source=undefined;
    const record = {
      handle:snapshot,
      ...actual,
      bytes:captureBytes?Buffer.concat(captured):undefined,
      stream:()=>snapshot.createReadStream({start:0,autoClose:false}),
      close:()=>snapshot.close(),
    };
    SNAPSHOT_RECORDS.set(record,record);
    return record;
  } catch (error) {
    await source?.close().catch(()=>{});
    await snapshot?.close().catch(()=>{});
    if (error instanceof Error && [tooLargeMessage,'artifact does not match manifest'].includes(error.message)) throw error;
    throw new Error('artifact cannot be opened as a verified regular file');
  }
}

export async function snapshotRegularFile(path,maximumSize=CATALOG_SIZE_LIMIT){return snapshotPath(path,null,maximumSize);}

function inside(parent, child) {
  const rel = relative(parent, child);
  return rel === '' || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel));
}

export async function loadRelease({ manifestFile, releaseDirectory }) {
  const directory = resolve(releaseDirectory);
  const manifestPath = resolve(manifestFile);
  if (manifestPath !== join(directory, 'manifest.json') || !inside(directory, manifestPath)) throw new Error('manifest path must be release-directory/manifest.json');
  await assertNoSymlinkComponents(directory);
  const directoryStats = await lstat(directory);
  if (!directoryStats.isDirectory() || directoryStats.isSymbolicLink()) throw new Error('release directory must be a regular directory');
  if (await realpath(directory) !== directory) throw new Error('release directory path is not canonical');
  const manifestSnapshot=await snapshotPath(manifestPath,null,MAX_MANIFEST_SIZE,true,'manifest is too large');
  let parsed;
  try{parsed=parseManifestBytes(manifestSnapshot.bytes);}catch(error){await manifestSnapshot.close().catch(()=>{});throw error;}
  try{return await finishLoadRelease(directory,parsed,manifestSnapshot);}catch(error){await manifestSnapshot.close().catch(()=>{});throw error;}
}

async function finishLoadRelease(directory,parsed,manifestSnapshot) {
  const expected = new Map([
    [parsed.packs[0].file, {sha1:parsed.packs[0].sha1,sha256:parsed.packs[0].sha256,size:parsed.packs[0].size,role:'content'}],
    [parsed.packs[1].file, {sha1:parsed.packs[1].sha1,sha256:parsed.packs[1].sha256,size:parsed.packs[1].size,role:'platform'}],
    [parsed.catalogFile, {sha256:parsed.manifest.catalog.sha256,size:parsed.manifest.catalog.size,role:'catalog',key:`${parsed.layout.root}/${parsed.catalogFile}`,contentType:'application/java-archive'}],
    ['manifest.json', {role:'manifest',key:`${parsed.layout.root}/manifest.json`,contentType:'application/json'}],
  ]);
  const entries = await readdir(directory, {withFileTypes:true});
  const names = entries.map(entry => entry.name).sort();
  const expectedNames = [...expected.keys()].sort();
  if (names.length !== 4 || names.some((name,index)=>name!==expectedNames[index])) throw new Error('release directory must contain exactly four manifest-bound artifacts');
  const artifacts = [];
  for (const name of names) {
    const entry = entries.find(candidate => candidate.name === name);
    if (!entry.isFile() || entry.isSymbolicLink()) throw new Error(`release artifact must be a regular file, not a symbolic link: ${name}`);
    const contract = expected.get(name);
    const path = join(directory, name);
    const actual = name==='manifest.json'?manifestSnapshot:await digestRegularFile(path,contract.size);
    if (contract.size !== undefined && actual.size !== contract.size) throw new Error(`release artifact size mismatch: ${name}`);
    if (contract.sha1 !== undefined && actual.sha1 !== contract.sha1) throw new Error(`release artifact digest mismatch: ${name}`);
    if (contract.sha256 !== undefined && actual.sha256 !== contract.sha256) throw new Error(`release artifact digest mismatch: ${name}`);
    artifacts.push({name,path,role:contract.role,key:contract.key ?? parsed.packs.find(pack=>pack.file===name)?.key,contentType:contract.contentType ?? 'application/zip',size:actual.size,sha1:actual.sha1,sha256:actual.sha256});
  }
  const byName = new Map(artifacts.map(artifact => [artifact.name, artifact]));
  const release = {
    ...parsed,
    directory,
    artifacts,
    packs: parsed.packs.map(pack => ({...pack, artifact:byName.get(pack.file)})),
    catalog: byName.get(parsed.catalogFile),
    manifestArtifact: byName.get('manifest.json'),
    layout: parsed.layout,
    close:()=>manifestSnapshot.close(),
  };
  const privateArtifacts=Object.freeze(artifacts.map(artifact=>Object.freeze({...artifact})));
  PRIVATE_ARTIFACT_SNAPSHOTS.set(privateArtifacts.find(artifact=>artifact.role==='manifest'),manifestSnapshot);
  const privateManifest=deepFreeze(structuredClone(parsed.manifest));
  const privateLayout=Object.freeze({...parsed.layout});
  const view=Object.freeze({manifest:privateManifest,layout:privateLayout,artifacts:privateArtifacts});
  RELEASE_CONTRACTS.set(release,Object.freeze({
    manifestBytes:Buffer.from(manifestSnapshot.bytes),directory,
    publicManifest:release.manifest,publicLayout:release.layout,publicArtifacts:release.artifacts,
    publicPacks:release.packs,publicCatalog:release.catalog,publicManifestArtifact:release.manifestArtifact,
    layout:privateLayout,artifacts:privateArtifacts,view,
  }));
  return release;
}
