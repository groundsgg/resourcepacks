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

export async function createReleaseFixture({ type = 'release' } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'resourcepacks-task8-release-'));
  const content = Buffer.from('content zip fixture');
  const platform = Buffer.from('platform zip fixture');
  const catalog = Buffer.from('catalog jar fixture');
  const contentSha1 = digest('sha1', content);
  const platformSha1 = digest('sha1', platform);
  const commit = '0123456789abcdef0123456789abcdef01234567';
  const version = type === 'release' ? '0.1.0' : '0.0.0-edge.42.g0123456789ab';
  const publication = type === 'release' ? { type: 'release', id: `v${version}` } : { type: 'build', id: commit };
  const objectRoot = type === 'release'
    ? `resourcepacks/packsets/grounds-global/releases/${publication.id}`
    : `resourcepacks/packsets/grounds-global/builds/${commit}`;
  const suffix = type === 'release' ? publication.id : `edge-${commit.slice(0, 12)}`;
  const names = { content: `grounds-content-pack-${suffix}.zip`, platform: `grounds-platform-pack-${suffix}.zip`, catalog: `grounds-resourcepack-catalog-${suffix}.jar` };
  const manifest = {
    catalog: {
      coordinate: `gg.grounds:resourcepacks-catalog:${version}`,
      file: names.catalog,
      id: 'grounds:resourcepacks',
      sha256: digest('sha256', catalog),
      size: catalog.length,
      version,
    },
    minecraft: { resourcePackFormat: 88, version: '26.2' },
    packSet: 'grounds-global',
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
        url: `https://cdn.grounds.gg/${objectRoot}/${names.content}`,
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
        url: `https://cdn.grounds.gg/${objectRoot}/${names.platform}`,
        uuid: '8da7cffe-bb04-55e0-9868-7789ce5de362',
      },
    ],
    provenance: {
      commit,
      repository: 'groundsgg/resourcepacks',
    },
    publication,
    schemaVersion: 2,
    version,
  };
  const files = new Map([
    [names.content, content],
    [names.platform, platform],
    [names.catalog, catalog],
  ]);
  for (const [name, bytes] of files) await writeFile(join(root, name), bytes);
  const manifestBytes = canonicalJson(manifest);
  await writeFile(join(root, 'manifest.json'), manifestBytes);
  files.set('manifest.json', Buffer.from(manifestBytes));
  return { root, manifest, files };
}
