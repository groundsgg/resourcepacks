import assert from 'node:assert/strict';
import test from 'node:test';

import { decodeChannel, encodeChannel } from '../src/channel.mjs';

const release = {type:'release',id:'v0.1.2'};
const build = {type:'build',id:'1969c1e6a3799e976de46eab019a16b2ee257ea7'};
const sha = 'a'.repeat(64);

test('channel codec byte-matches the Kotlin Stable fixture', () => {
  const bytes = encodeChannel({channel:'stable',sequence:7,publication:release,manifest:{sha256:sha,size:123}});
  assert.equal(bytes.toString('utf8'), `{
  "channel": "stable",
  "manifest": {
    "sha256": "${sha}",
    "size": 123,
    "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json"
  },
  "packSet": "grounds-global",
  "schemaVersion": 2,
  "sequence": 7,
  "target": {
    "id": "v0.1.2",
    "type": "release"
  }
}
`);
  assert.deepEqual(decodeChannel(bytes), {channel:'stable',sequence:7,target:release,manifest:{url:'https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json',sha256:sha,size:123}});
});

test('channel codec byte-matches the Kotlin Edge fixture', () => {
  const bytes = encodeChannel({channel:'edge',sequence:8,publication:build,manifest:{sha256:'b'.repeat(64),size:456}});
  assert.equal(bytes.toString('utf8'), `{
  "channel": "edge",
  "manifest": {
    "sha256": "${'b'.repeat(64)}",
    "size": 456,
    "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/1969c1e6a3799e976de46eab019a16b2ee257ea7/manifest.json"
  },
  "packSet": "grounds-global",
  "schemaVersion": 2,
  "sequence": 8,
  "target": {
    "id": "1969c1e6a3799e976de46eab019a16b2ee257ea7",
    "type": "build"
  }
}
`);
});

test('channel codec rejects noncanonical and duplicate pointer JSON', () => {
  const canonical = encodeChannel({channel:'stable',sequence:7,publication:release,manifest:{sha256:sha,size:123}});
  for (const bytes of [Buffer.concat([Buffer.from([0xef,0xbb,0xbf]),canonical]), Buffer.from(canonical.toString().replace('  "channel"', ' "channel"')), Buffer.from(canonical.toString().replace('  "channel": "stable",', '  "channel": "stable",\n  "channel": "stable",'))]) {
    assert.throws(() => decodeChannel(bytes), /channel validation failed/);
  }
});

test('channel codec rejects hostile UTF-8, every structural type, and parser-limit overflows', () => {
  const canonical = encodeChannel({channel:'stable',sequence:7,publication:release,manifest:{sha256:sha,size:123}}).toString();
  const bad = [
    Buffer.from([0xc3]),
    ...[[0xef,0xbb,0xbf],[0xfe,0xff],[0xff,0xfe],[0,0,0xfe,0xff],[0xff,0xfe,0,0]].map(prefix => Buffer.concat([Buffer.from(prefix),Buffer.from(canonical)])),
    Buffer.from(`${canonical}x`),
    Buffer.from(canonical.replace('"sequence": 7','"sequence": 1.5')),
    Buffer.from(canonical.replace('"size": 123','"size": 1.5')),
    Buffer.from(canonical.replace('"sequence": 7','"sequence": 9223372036854775808')),
    Buffer.from(canonical.replace('"channel": "stable"','"channel": null')),
    Buffer.from(canonical.replace('"target": {','"target": null,\n  "target2": {')),
    Buffer.from(canonical.replace('"sha256": "'+sha+'"','"sha256": null')),
    Buffer.from(canonical.replace('"sequence": 7','"unknown": true,\n  "sequence": 7')),
    Buffer.from(canonical.replace('  "sequence": 7,\n','')),
    Buffer.from(canonical.replace('"type": "release"','"type": "build"')),
    Buffer.from(canonical.replace('v0.1.2','v01.2')),
    Buffer.from(canonical.replace(sha,'A'.repeat(64))),
    Buffer.from(canonical.replace('"size": 123','"size": 0')),
    Buffer.from(canonical.replace('"url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json"','"url": "https://attacker.example/x"')),
    Buffer.from(canonical.replace('"sequence": 7','"padding": "'+'x'.repeat(16385)+'",\n  "sequence": 7')),
    Buffer.from(canonical.replace('"sequence": 7','"sequence": '+'1'.repeat(129))),
    Buffer.from('{"x":'.repeat(66)+'0'+'}'.repeat(66)),
    Buffer.alloc(1024*1024+1, 0x20),
  ];
  for (const bytes of bad) assert.throws(() => decodeChannel(bytes), /channel validation failed/);
});

test('channel parser matches Kotlin UTF-16 string bounds and rejects unpaired surrogate escapes', () => {
  const canonical=encodeChannel({channel:'stable',sequence:7,publication:release,manifest:{sha256:sha,size:123}}).toString();
  const atLimit='😀'.repeat(8192);
  assert.throws(()=>decodeChannel(Buffer.from(canonical.replace('"sequence": 7',`"padding": "${atLimit}",\n  "sequence": 7`))), /unexpected fields/);
  for(const replacement of [`"padding": "${'😀'.repeat(8193)}",\n  "sequence": 7`, '"padding": "\\ud800",\n  "sequence": 7', '"padding": "\\udc00",\n  "sequence": 7']) assert.throws(()=>decodeChannel(Buffer.from(canonical.replace('"sequence": 7',replacement))),/channel validation failed/);
});
