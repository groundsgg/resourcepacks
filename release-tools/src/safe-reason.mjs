// Names why an R2 call failed without echoing anything the caller supplied. Only the S3 error
// name (NoSuchBucket, AccessDenied, InvalidAccessKeyId, …), a Node network code (ENOTFOUND, …)
// and the HTTP status are kept, and only when they look like identifiers: an error message can
// carry the endpoint or a signed request, so it is never included.
const IDENTIFIER = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/;

export function safeReason(error) {
  const parts = [];
  const name = error?.name;
  if (typeof name === 'string' && IDENTIFIER.test(name) && name !== 'Error') parts.push(name);
  const code = error?.code ?? error?.cause?.code;
  if (typeof code === 'string' && IDENTIFIER.test(code) && code !== name) parts.push(code);
  const status = error?.$metadata?.httpStatusCode;
  if (Number.isSafeInteger(status) && status >= 100 && status <= 599) parts.push(`HTTP ${status}`);
  return parts.length ? ` (${parts.join(', ')})` : '';
}
