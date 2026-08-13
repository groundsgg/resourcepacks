import { createHash } from 'node:crypto';

const MAX_STREAM_SIZE = 1024 * 1024 * 1024;

function asBuffer(chunk) {
  return Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
}

/** Streams a body once, never trusting a peer-supplied content length. */
export async function digestStream(stream, maximumSize = MAX_STREAM_SIZE) {
  const sha1 = createHash('sha1');
  const sha256 = createHash('sha256');
  let size = 0;
  for await (const chunk of stream) {
    const bytes = asBuffer(chunk);
    size += bytes.length;
    if (size > maximumSize) throw new Error(`stream exceeds safe limit of ${maximumSize} bytes`);
    sha1.update(bytes);
    sha256.update(bytes);
  }
  return { sha1: sha1.digest('hex'), sha256: sha256.digest('hex'), size };
}

/** Compares two streams incrementally without retaining either complete body. */
export async function sameStreamBytes(left, right, maximumSize = MAX_STREAM_SIZE) {
  const leftIterator = left[Symbol.asyncIterator]();
  const rightIterator = right[Symbol.asyncIterator]();
  try {
    let leftPending = Buffer.alloc(0);
    let rightPending = Buffer.alloc(0);
    let consumed = 0;
    while (true) {
      if (leftPending.length === 0) {
        const next = await leftIterator.next();
        leftPending = next.done ? null : asBuffer(next.value);
      }
      if (rightPending.length === 0) {
        const next = await rightIterator.next();
        rightPending = next.done ? null : asBuffer(next.value);
      }
      if (leftPending === null || rightPending === null) return leftPending === rightPending;
      const compared = Math.min(leftPending.length, rightPending.length);
      consumed += compared;
      if (consumed > maximumSize) throw new Error(`stream exceeds safe limit of ${maximumSize} bytes`);
      if (!leftPending.subarray(0, compared).equals(rightPending.subarray(0, compared))) return false;
      leftPending = leftPending.subarray(compared);
      rightPending = rightPending.subarray(compared);
    }
  } finally {
    await Promise.allSettled([leftIterator.return?.(),rightIterator.return?.()]);
  }
}
