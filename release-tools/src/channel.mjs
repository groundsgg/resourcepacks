const MAX_DOCUMENT_SIZE = 1024 * 1024;
const MAX_DEPTH = 64;
const MAX_STRING = 16_384;
const MAX_NUMBER = 128;
const HEX40 = /^[0-9a-f]{40}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

function fail(detail) { throw new Error(`channel validation failed: ${detail}`); }

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}

function exact(value, keys, pointer) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${pointer} must be an object`);
  const actual = Object.keys(value).sort(); const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) fail(`${pointer} has unexpected fields`);
}

function urlFor(publication) {
  return `https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/${publication.type === 'release' ? 'releases' : 'builds'}/${publication.id}/manifest.json`;
}

function validate({channel, sequence, publication, manifest}) {
  if (!['stable','edge'].includes(channel)) fail('channel must be stable or edge');
  if (!Number.isSafeInteger(sequence) || sequence <= 0) fail('sequence must be a positive safe integer');
  exact(publication, ['type','id'], '/target');
  if (!['release','build'].includes(publication.type)) fail('target type mismatch');
  if (channel === 'stable' && publication.type !== 'release') fail('Stable channels require release targets');
  if (channel === 'edge' && publication.type !== 'build') fail('Edge channels require build targets');
  if (publication.type === 'release' && (!publication.id.startsWith('v') || !SEMVER.test(publication.id.slice(1)))) fail('release target ID mismatch');
  if (publication.type === 'build' && !HEX40.test(publication.id)) fail('build target ID mismatch');
  exact(manifest, ['sha256','size'], '/manifest');
  if (!HEX64.test(manifest.sha256) || !Number.isSafeInteger(manifest.size) || manifest.size <= 0 || manifest.size > MAX_DOCUMENT_SIZE) fail('manifest reference mismatch');
}

export function encodeChannel({channel, sequence, publication, manifest}) {
  validate({channel,sequence,publication,manifest});
  return Buffer.from(`${JSON.stringify(canonical({schemaVersion:2,packSet:'grounds-global',channel,sequence,target:{type:publication.type,id:publication.id},manifest:{url:urlFor(publication),sha256:manifest.sha256,size:manifest.size}}), null, 2)}\n`, 'utf8');
}

export function decodeChannel(bytes) {
  if (!Buffer.isBuffer(bytes) && !(bytes instanceof Uint8Array)) fail('input must be bytes');
  if (bytes.length > MAX_DOCUMENT_SIZE) fail('input exceeds parser limit');
  let text;
  try { text = new TextDecoder('utf-8', {fatal:true,ignoreBOM:true}).decode(bytes); } catch { fail('input is not UTF-8'); }
  if (text.charCodeAt(0) === 0xfeff) fail('byte-order marks are not permitted');
  let document;
  try { document = parseStrict(text); } catch (error) { fail(error instanceof Error ? error.message : 'invalid JSON'); }
  exact(document, ['schemaVersion','packSet','channel','sequence','target','manifest'], '/');
  exact(document.target, ['type','id'], '/target'); exact(document.manifest, ['url','sha256','size'], '/manifest');
  if (document.schemaVersion !== 2 || document.packSet !== 'grounds-global') fail('channel identity mismatch');
  validate({channel:document.channel,sequence:document.sequence,publication:document.target,manifest:{sha256:document.manifest.sha256,size:document.manifest.size}});
  if (document.manifest.url !== urlFor(document.target)) fail('manifest URL mismatch');
  const result = {channel:document.channel,sequence:document.sequence,target:{type:document.target.type,id:document.target.id},manifest:{url:document.manifest.url,sha256:document.manifest.sha256,size:document.manifest.size}};
  if (!Buffer.from(text, 'utf8').equals(encodeChannel({channel:result.channel,sequence:result.sequence,publication:result.target,manifest:{sha256:result.manifest.sha256,size:result.manifest.size}}))) fail('JSON is not canonical');
  return result;
}

function parseStrict(text) {
  let offset = 0;
  const ws = () => { while (/[\t\n\r ]/.test(text[offset] ?? '')) offset += 1; };
  const value = (depth = 0) => {
    if (depth > MAX_DEPTH) throw new Error('maximum nesting depth exceeded');
    ws(); const first = text[offset];
    if (first === '{') return object(depth); if (first === '[') return array(depth); if (first === '"') return string();
    if (text.startsWith('true',offset)) { offset += 4; return true; }
    if (text.startsWith('false',offset)) { offset += 5; return false; }
    if (text.startsWith('null',offset)) { offset += 4; return null; }
    const match = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(offset));
    if (!match) throw new Error('invalid JSON'); if (match[0].length > MAX_NUMBER) throw new Error('number exceeds parser limit'); offset += match[0].length; return Number(match[0]);
  };
  const string = () => { const start = offset; offset += 1; let escaped = false; while (offset < text.length) { const char = text[offset++]; if (escaped) { escaped = false; continue; } if (char === '\\') { escaped = true; continue; } if (char === '"') { const result=JSON.parse(text.slice(start,offset)); if (result.length > MAX_STRING || /(?:[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff])/.test(result)) throw new Error('string exceeds parser limit'); return result; } if (char < ' ') throw new Error('invalid JSON'); } throw new Error('invalid JSON'); };
  const object = depth => { const result = Object.create(null); const keys = new Set(); offset += 1; ws(); if (text[offset] === '}') { offset += 1; return result; } while (true) { ws(); if (text[offset] !== '"') throw new Error('invalid JSON'); const key = string(); if (keys.has(key)) throw new Error('duplicate key'); keys.add(key); ws(); if (text[offset++] !== ':') throw new Error('invalid JSON'); result[key] = value(depth + 1); ws(); if (text[offset] === '}') { offset += 1; return result; } if (text[offset++] !== ',') throw new Error('invalid JSON'); } };
  const array = depth => { const result=[]; offset += 1; ws(); if (text[offset] === ']') { offset += 1; return result; } while (true) { result.push(value(depth + 1)); ws(); if (text[offset] === ']') { offset += 1; return result; } if (text[offset++] !== ',') throw new Error('invalid JSON'); } };
  const result = value(); ws(); if (offset !== text.length) throw new Error('trailing JSON input'); return result;
}
