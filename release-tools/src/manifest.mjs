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

export function canonicalManifestText(value) {
  return `${JSON.stringify(canonicalize(value), null, 2)}\n`;
}

function validatePack(pack, expected, version) {
  exactKeys(pack, ['id','order','required','resourcePackFormat','role','sha1','sha256','size','url','uuid'], `/packs/${expected.order}`);
  if (pack.order !== expected.order || pack.role !== expected.role || pack.id !== `grounds-${expected.role}` || pack.uuid !== expected.uuid) fail(`pack ${expected.role} identity mismatch`);
  if (pack.required !== true || pack.resourcePackFormat !== 88) fail(`pack ${expected.role} policy mismatch`);
  if (!HEX40.test(pack.sha1) || !HEX64.test(pack.sha256)) fail(`pack ${expected.role} digest mismatch`);
  positiveInteger(pack.size, `/packs/${expected.order}/size`);
  if (pack.size > expected.maximumSize) fail(`pack ${expected.role} size exceeds product limit`);
  const expectedUrl = `https://cdn.grounds.gg/resourcepacks/${expected.role}/${pack.sha1}.zip`;
  if (pack.url !== expectedUrl) fail(`pack ${expected.role} URL mismatch`);
  return {
    ...pack,
    file: `grounds-${expected.role}-${pack.sha1}.zip`,
    key: `resourcepacks/${expected.role}/${pack.sha1}.zip`,
    version,
  };
}

export function validateManifest(manifest) {
  exactKeys(manifest, ['catalog','id','minecraft','packs','provenance','schemaVersion','version'], '/');
  if (manifest.schemaVersion !== 1 || manifest.id !== 'grounds:global' || typeof manifest.version !== 'string' || !SEMVER.test(manifest.version)) fail('root identity mismatch');
  exactKeys(manifest.minecraft, ['resourcePackFormat','version'], '/minecraft');
  if (manifest.minecraft.version !== '26.2' || manifest.minecraft.resourcePackFormat !== 88) fail('Minecraft target mismatch');
  exactKeys(manifest.catalog, ['coordinate','file','id','sha256','size','version'], '/catalog');
  const catalogFile = `grounds-resourcepacks-catalog-${manifest.version}.jar`;
  if (manifest.catalog.id !== 'grounds:resourcepacks' || manifest.catalog.version !== manifest.version || manifest.catalog.coordinate !== `gg.grounds:resourcepacks-catalog:${manifest.version}` || manifest.catalog.file !== catalogFile || !HEX64.test(manifest.catalog.sha256)) fail('catalog identity mismatch');
  positiveInteger(manifest.catalog.size, '/catalog/size');
  if (manifest.catalog.size > CATALOG_SIZE_LIMIT) fail('catalog size exceeds product limit');
  if (!Array.isArray(manifest.packs) || manifest.packs.length !== 2) fail('packs must contain exactly content and platform');
  const packs = [
    validatePack(manifest.packs[0], {order:0,role:'content',uuid:CONTENT_UUID,maximumSize:CONTENT_SIZE_LIMIT}, manifest.version),
    validatePack(manifest.packs[1], {order:1,role:'platform',uuid:PLATFORM_UUID,maximumSize:PLATFORM_SIZE_LIMIT}, manifest.version),
  ];
  exactKeys(manifest.provenance, ['commit','repository','tag'], '/provenance');
  if (manifest.provenance.repository !== 'groundsgg/resourcepacks' || manifest.provenance.tag !== `v${manifest.version}` || !HEX40.test(manifest.provenance.commit)) fail('provenance mismatch');
  return { manifest, packs, catalogFile };
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
  if(artifact.snapshot){
    if(artifact.snapshot.size!==artifact.size||artifact.snapshot.sha1!==artifact.sha1||artifact.snapshot.sha256!==artifact.sha256)throw new Error('artifact snapshot contract mismatch');
    return {size:artifact.snapshot.size,stream:()=>artifact.snapshot.handle.createReadStream({start:0,autoClose:false}),close:async()=>{}};
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
    return {
      handle:snapshot,
      ...actual,
      bytes:captureBytes?Buffer.concat(captured):undefined,
      stream:()=>snapshot.createReadStream({start:0,autoClose:false}),
      close:()=>snapshot.close(),
    };
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
    [parsed.catalogFile, {sha256:parsed.manifest.catalog.sha256,size:parsed.manifest.catalog.size,role:'catalog'}],
    ['manifest.json', {role:'manifest'}],
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
    artifacts.push({name,path,role:contract.role,size:actual.size,sha1:actual.sha1,sha256:actual.sha256,snapshot:name==='manifest.json'?manifestSnapshot:undefined});
  }
  const byName = new Map(artifacts.map(artifact => [artifact.name, artifact]));
  return {
    ...parsed,
    directory,
    artifacts,
    packs: parsed.packs.map(pack => ({...pack, artifact:byName.get(pack.file)})),
    catalog: byName.get(parsed.catalogFile),
    manifestArtifact: byName.get('manifest.json'),
    close:()=>manifestSnapshot.close(),
  };
}
