export class CliUsageError extends Error {}

export function strictArgs(args, allowed) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index]; const value = args[index + 1];
    if (!allowed.includes(key) || value === undefined || !value || values.has(key)) throw new CliUsageError('invalid command arguments');
    values.set(key, value);
  }
  if (values.size !== allowed.length) throw new CliUsageError('missing required command arguments');
  return Object.fromEntries(values);
}

export async function runCli(operation) {
  try {
    const output = await operation();
    if (output !== undefined) await new Promise(resolve => process.stdout.write(`${output}\n`, resolve));
  } catch (error) {
    const usage = error instanceof CliUsageError;
    const message = error instanceof Error ? error.message : 'operation failed';
    await new Promise(resolve => process.stderr.write(`error: ${message}\n`, resolve));
    process.exitCode = usage ? 2 : 1;
  }
}
