import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import test from 'node:test';

import { digestStream, sameStreamBytes } from '../src/digests.mjs';

test('digestStream consumes partial chunks and reports both hashes and measured size', async () => {
  const result = await digestStream(Readable.from([Buffer.from('gr'), Buffer.from('ounds')]));

  assert.deepEqual(result, {
    sha1: '9c23d29bf87ae13721ae38fd1c79812769e5fc7e',
    sha256: '5f989a8e7bd59411991daa3ce519114ef56fb1ea509b96bfcee34b0fe63a90ff',
    size: 7,
  });
});

test('sameStreamBytes detects a differing byte despite equal chunk sizes', async () => {
  const same = await sameStreamBytes(
    Readable.from([Buffer.from('abc'), Buffer.from('def')]),
    Readable.from([Buffer.from('abc'), Buffer.from('deg')]),
  );

  assert.equal(same, false);
});

test('sameStreamBytes closes both iterators after an early mismatch', async () => {
  let leftClosed = false; let rightClosed = false;
  async function* left() { try { yield Buffer.from('a'); yield Buffer.from('never'); } finally { leftClosed = true; } }
  async function* right() { try { yield Buffer.from('b'); yield Buffer.from('never'); } finally { rightClosed = true; } }
  assert.equal(await sameStreamBytes(left(),right()),false);
  assert.equal(leftClosed,true);
  assert.equal(rightClosed,true);
});
