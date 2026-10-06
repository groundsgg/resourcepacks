import assert from 'node:assert/strict';
import test from 'node:test';

import { safeReason } from '../src/safe-reason.mjs';

test('safeReason keeps the S3 error name, network code and HTTP status', () => {
  assert.equal(safeReason({ name: 'NoSuchBucket', $metadata: { httpStatusCode: 404 } }), ' (NoSuchBucket, HTTP 404)');
  assert.equal(safeReason(Object.assign(new Error('getaddrinfo ENOTFOUND acct.r2.example'), { code: 'ENOTFOUND' })), ' (ENOTFOUND)');
});

test('safeReason never echoes messages or non-identifier values', () => {
  const secretish = 'https://user:pass@acct.r2.example/bucket?X-Amz-Signature=abc';
  const reason = safeReason({ name: secretish, code: secretish, message: secretish, $metadata: { httpStatusCode: 'x' } });
  assert.equal(reason, '');
  assert.equal(safeReason(new Error(secretish)), '');
  assert.equal(safeReason(undefined), '');
});
