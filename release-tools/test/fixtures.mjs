import { createHash } from 'node:crypto';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

function digest(algorithm, bytes) {
  return createHash(algorithm).update(bytes).digest('hex');
}

export function canonicalJson(value) {
  const normalize = input => {
    if (Array.isArray(input)) return input.map(normalize);
    if (input !== null && typeof input === 'object') {
      return Object.fromEntries(
        Object.keys(input).sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
          .map(key => [key, normalize(input[key])]),
      );
    }
    return input;
  };
  return `${JSON.stringify(normalize(value), null, 2)}\n`;
}

export async function createReleaseFixture() {
  const root = await mkdtemp(join(tmpdir(), 'resourcepacks-task8-release-'));
  const content = Buffer.from('content zip fixture');
  const platform = Buffer.from('platform zip fixture');
  const catalog = Buffer.from('catalog jar fixture');
  const contentSha1 = digest('sha1', content);
  const platformSha1 = digest('sha1', platform);
  const manifest = {
    catalog: {
      coordinate: 'gg.grounds:resourcepacks-catalog:0.1.0',
      file: 'grounds-resourcepacks-catalog-0.1.0.jar',
      id: 'grounds:resourcepacks',
      sha256: digest('sha256', catalog),
      size: catalog.length,
      version: '0.1.0',
    },
    id: 'grounds:global',
    minecraft: { resourcePackFormat: 88, version: '26.2' },
    packs: [
      {
        id: 'grounds-content',
        order: 0,
        required: true,
        resourcePackFormat: 88,
        role: 'content',
        sha1: contentSha1,
        sha256: digest('sha256', content),
        size: content.length,
        url: `https://cdn.grounds.gg/resourcepacks/content/${contentSha1}.zip`,
        uuid: '44591d5b-71f5-5c2a-a5b2-d3ee7be47e53',
      },
      {
        id: 'grounds-platform',
        order: 1,
        required: true,
        resourcePackFormat: 88,
        role: 'platform',
        sha1: platformSha1,
        sha256: digest('sha256', platform),
        size: platform.length,
        url: `https://cdn.grounds.gg/resourcepacks/platform/${platformSha1}.zip`,
        uuid: '8da7cffe-bb04-55e0-9868-7789ce5de362',
      },
    ],
    provenance: {
      commit: '0123456789abcdef0123456789abcdef01234567',
      repository: 'groundsgg/resourcepacks',
      tag: 'v0.1.0',
    },
    schemaVersion: 1,
    version: '0.1.0',
  };
  const files = new Map([
    [`grounds-content-${contentSha1}.zip`, content],
    [`grounds-platform-${platformSha1}.zip`, platform],
    ['grounds-resourcepacks-catalog-0.1.0.jar', catalog],
  ]);
  for (const [name, bytes] of files) await writeFile(join(root, name), bytes);
  const manifestBytes = canonicalJson(manifest);
  await writeFile(join(root, 'manifest.json'), manifestBytes);
  files.set('manifest.json', Buffer.from(manifestBytes));
  return { root, manifest, files };
}
