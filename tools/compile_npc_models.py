#!/usr/bin/env python3
"""Compile licensed Blockbench NPC archives into deterministic Grounds resources."""
import argparse
import atexit
import functools
import subprocess
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import tempfile
import zipfile

COLLECTIONS = ('craftmasters', 'guildfolk', 'townfolk')
SOUND_CLIPS = {'craftmasters': ('idle2', 'wave'), 'guildfolk': ('idle2', 'hello')}
FACES = {'north', 'south', 'east', 'west', 'up', 'down'}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n', encoding='utf-8')


def group_rigid_parts(parts, output):
    """Share one display for cubes with exactly the same bone and linear transform.

    Arbitrary authored cube rotations stay on the display, not baked into vanilla's
    restricted element rotations. Different pivots can share only at zero rotation.
    Split large groups so every native element stays within Minecraft's [-16, 32].
    """
    buckets, groups = {}, []
    for part in parts:
        path = output / f'pack/assets/grounds/models/{part["model"].split(":", 1)[1]}.json'
        native = json.loads(path.read_text())
        element = native['elements'][0]
        size = [b - a for a, b in zip(element['from'], element['to'])]
        center = [v * 16 for v in part['center']]
        lo = [c - s / 2 for c, s in zip(center, size)]
        hi = [c + s / 2 for c, s in zip(center, size)]
        rotation = tuple(part['rotation'])
        scale = tuple(part.get('scale', [1, 1, 1]))
        pivot = tuple(part['pivot']) if any(rotation) else None
        key = (part['bone'], rotation, scale, pivot)
        group = next((g for g in buckets.get(key, []) if all(
            max(g['hi'][i], hi[i]) - min(g['lo'][i], lo[i]) <= 48
            for i in range(3))), None)
        if group is None:
            group = {'parts': [], 'lo': lo, 'hi': hi}
            buckets.setdefault(key, []).append(group)
            groups.append(group)
        group['parts'].append((part, native, size))
        group['lo'] = [min(a, b) for a, b in zip(group['lo'], lo)]
        group['hi'] = [max(a, b) for a, b in zip(group['hi'], hi)]
    result = []
    for group in groups:
        first, native, _ = group['parts'][0]
        if len(group['parts']) == 1:
            result.append(first)
            continue
        center = [(a + b) / 32 for a, b in zip(group['lo'], group['hi'])]
        scale = first.get('scale', [1, 1, 1])
        elements = []
        for part, model, size in group['parts']:
            offset = [8 + (p - c) * 16 / s for p, c, s in zip(part['center'], center, scale)]
            elements.append(dict(model['elements'][0], **{
                'from': [c - s / 2 for c, s in zip(offset, size)],
                'to': [c + s / 2 for c, s in zip(offset, size)],
            }))
            if part is not first:
                suffix = part['model'].split(':', 1)[1] + '.json'
                (output / f'pack/assets/grounds/models/{suffix}').unlink()
                (output / f'pack/assets/grounds/items/{suffix}').unlink()
        native['elements'] = elements
        suffix = first['model'].split(':', 1)[1] + '.json'
        write_json(output / f'pack/assets/grounds/models/{suffix}', native)
        result.append(dict(first, center=center))
    return result


def isolate_part_models(parts, output):
    """Bind item/native model IDs to content so legacy packs cannot shadow grouped meshes."""
    result = []
    for part in parts:
        suffix = part['model'].split(':', 1)[1]
        old_model = output / f'pack/assets/grounds/models/{suffix}.json'
        fingerprint = hashlib.sha256(old_model.read_bytes()).hexdigest()
        isolated = f'{suffix}_{fingerprint}'
        old_model.replace(output / f'pack/assets/grounds/models/{isolated}.json')
        write_json(output / f'pack/assets/grounds/items/{isolated}.json',
                   {'model': {'type': 'minecraft:model', 'model': f'grounds:{isolated}'}})
        (output / f'pack/assets/grounds/items/{suffix}.json').unlink()
        result.append(dict(part, model=f'grounds:{isolated}'))
    return result


def multiply_matrix(left, right):
    return [sum(left[row * 4 + k] * right[k * 4 + col] for k in range(4))
            for row in range(4) for col in range(4)]


def translation_matrix(value):
    return [1, 0, 0, value[0], 0, 1, 0, value[1], 0, 0, 1, value[2], 0, 0, 0, 1]


def composite_part_matrix(part, origin):
    """Preserve the runtime's T(pivot-origin) Rz Ry Rx T(center-pivot) S.

    ItemTransform centers native vertices BEFORE the submodel transformation:
    its matrix therefore needs T(.5) L T(-.5), in row-major codec order.
    """
    x, y, z = map(math.radians, part['rotation'])
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = [1, 0, 0, 0, 0, cx, -sx, 0, 0, sx, cx, 0, 0, 0, 0, 1]
    ry = [cy, 0, sy, 0, 0, 1, 0, 0, -sy, 0, cy, 0, 0, 0, 0, 1]
    rz = [cz, -sz, 0, 0, sz, cz, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    scale = part.get('scale', [1, 1, 1])
    sm = [scale[0], 0, 0, 0, 0, scale[1], 0, 0, 0, 0, scale[2], 0, 0, 0, 0, 1]
    matrices = [translation_matrix([a - b for a, b in zip(part['pivot'], origin)]),
                rz, ry, rx,
                translation_matrix([a - b for a, b in zip(part['center'], part['pivot'])]),
                sm]
    result = matrices[0]
    for matrix in matrices[1:]:
        result = multiply_matrix(result, matrix)
    # Match runtime cancellation before adding the half-block item centering.
    return multiply_matrix(multiply_matrix(translation_matrix([.5] * 3), result),
                           translation_matrix([-.5] * 3))


def eligible_composite_bones(policy, data, asset, parts):
    if policy is None: return None
    if not isinstance(policy, dict) or type(policy.get('schemaVersion')) is not int or policy['schemaVersion'] != 1:
        raise ValueError('invalid composite eligibility schema')
    if policy.get('compilerSha256') != compiler_identity():
        raise ValueError('composite eligibility belongs to another compiler')
    models = policy.get('models')
    if not isinstance(models, dict) or asset not in models: raise ValueError(f'missing composite eligibility: {asset}')
    entry = models[asset]
    source = hashlib.sha256(json.dumps(data, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
    if not isinstance(entry, dict) or entry.get('sourceSha256') != source: raise ValueError(f'stale composite source: {asset}')
    allowed = entry.get('bones')
    if not isinstance(allowed, list) or len(allowed) > 256 or any(x is not None and not isinstance(x, str) for x in allowed):
        raise ValueError('invalid composite bone eligibility')
    if len(allowed) != len(set(allowed)) or not set(allowed) <= {p['bone'] for p in parts}:
        raise ValueError('unknown or duplicate composite bone eligibility')
    return set(allowed)


def group_bone_composites(parts, bones, output, allowed=None):
    """Keep rigid leaf files intact; move their fixed transforms into item layers."""
    origins = {bone['id']: bone['origin'] for bone in bones}
    groups = {}
    for part in parts:
        groups.setdefault(part['bone'], []).append(part)
    result = []
    for bone, leaves in groups.items():
        if len(leaves) == 1 or (allowed is not None and bone not in allowed):
            result.extend(leaves)
            continue
        origin = origins.get(bone, [0, 0, 0])
        matrices = [composite_part_matrix(part, origin) for part in leaves]
        # A finite JSON double can overflow the actual client float matrix codec.
        if any(not math.isfinite(v) or abs(v) > 3.4028234663852886e38
               for matrix in matrices for v in matrix):
            result.extend(leaves)
            continue
        # Center the shared entity near its leaves to reduce chord-vs-arc error
        # during native interpolation. Recover the representable anchor after
        # adding the bone origin; a large origin must not lose an unaccounted offset.
        centers = [[sum(matrix[i * 4 + j] * .5 for j in range(3)) + matrix[i * 4 + 3] - .5
                    for i in range(3)] for matrix in matrices]
        center = [origin[i] + (min(c[i] for c in centers) + max(c[i] for c in centers)) / 2
                  for i in range(3)]
        anchor = [c - o for c, o in zip(center, origin)]
        for matrix in matrices:
            for i in range(3): matrix[i * 4 + 3] -= anchor[i]
        if any(not math.isfinite(v) or abs(v) > 3.4028234663852886e38
               for matrix in matrices for v in matrix) or not all(math.isfinite(v) for v in center):
            result.extend(leaves)
            continue
        node = {'type': 'minecraft:composite', 'models': [
            {'type': 'minecraft:model', 'model': part['model'], 'transformation': matrix}
            for part, matrix in zip(leaves, matrices)]}
        fingerprint = hashlib.sha256(json.dumps(node, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
        # Leaf IDs already include their mesh/material digest; the root also
        # includes every matrix value, so an old pack cannot shadow either layer.
        prefix = leaves[0]['model'].split(':', 1)[1].rsplit('/', 1)[0]
        model = f'grounds:{prefix}/part_{len(result):03d}_{fingerprint}'
        write_json(output / f'pack/assets/grounds/items/{model.split(":", 1)[1]}.json', {'model': node})
        result.append({'bone': bone, 'model': model, 'center': center,
                       'pivot': list(center), 'rotation': [0, 0, 0]})
    return result


def importer_classpath():
    value = os.environ.get('NPC_IMPORTER_CLASSPATH')
    if not value or any(not Path(p).is_file() for p in value.split(os.pathsep)):
        raise ValueError('Set NPC_IMPORTER_CLASSPATH to the pinned npc-cli runtime classpath')
    return value


def compiler_identity():
    value = hashlib.sha256(Path(__file__).read_bytes())
    value.update(Path(__file__).with_name('GroundsNpcImporter.java').read_bytes())
    for item in importer_classpath().split(os.pathsep):
        value.update(hashlib.sha256(Path(item).read_bytes()).digest())
    return value.hexdigest()


@functools.lru_cache(maxsize=1)
def importer_bridge():
    temporary = tempfile.TemporaryDirectory(prefix='grounds-npc-importer-')
    atexit.register(temporary.cleanup)
    directory = Path(temporary.name)
    home = os.environ.get('JAVA_HOME')
    java = str(Path(home) / 'bin/java') if home else 'java'
    javac = str(Path(home) / 'bin/javac') if home else 'javac'
    run = subprocess.run([javac, '-cp', importer_classpath(), '-d', str(directory),
                          str(Path(__file__).with_name('GroundsNpcImporter.java'))],
                         capture_output=True, text=True, timeout=120)
    if run.returncode: raise ValueError('Cannot build shared NPC adapter: ' + run.stderr)
    return java, str(directory) + os.pathsep + importer_classpath()


def run_importer(*args):
    java, classpath = importer_bridge()
    run = subprocess.run([java, '-cp', classpath, 'GroundsNpcImporter', *map(str, args)],
                         capture_output=True, text=True, timeout=120)
    if run.returncode: raise ValueError('Shared NPC importer failed: ' + run.stderr)


def package_content(output):
    run_importer('package', output, 'grounds:' + compiler_identity())


def validate_content(content, pack):
    run_importer('verify', content, pack)


def compile_model(data, collection, character, output, geometry_mode='rigid', composite_policy=None):
    """Import with the public library, then apply the qualified Grounds mesh optimizer."""
    if geometry_mode not in ('rigid', 'composite'): raise ValueError('invalid NPC geometry mode')
    if composite_policy is not None and geometry_mode != 'composite': raise ValueError('composite policy requires composite mode')
    output = Path(output)
    asset = f'grounds:npc/{collection}/{character}'
    with tempfile.TemporaryDirectory(prefix='grounds-npc-model-') as temporary:
        source = Path(temporary) / 'source.bbmodel'
        # Preserve authored encoding and duplicate keys for the one authoritative parser.
        if isinstance(data, bytes): source.write_bytes(data)
        else: write_json(source, data)
        imported = Path(temporary) / 'imported'
        run_importer('import', source, asset, imported)
        model = json.loads((imported / 'model.json').read_bytes())
        for part in model['parts']: part.setdefault('bone', None)
        for bone in model['bones']: bone.setdefault('parent', None)
        # The particle slot's indirection is kept for compatibility with old Grounds packs.
        for path in (imported / 'pack').rglob('*'):
            if not path.is_file(): continue
            relative = path.relative_to(imported / 'pack')
            target = output / 'pack' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if str(relative).startswith('assets/grounds/models/'):
                native = json.loads(path.read_bytes())
                textures = native['textures']
                textures['particle'] = '#' + next(k for k,v in textures.items() if k != 'particle' and v == textures['particle'])
                write_json(target, native)
            else: shutil.copyfile(path, target)
    parts = isolate_part_models(group_rigid_parts(model['parts'], output), output)
    if geometry_mode == 'composite':
        policy_data = json.loads(data.decode('utf-8')) if isinstance(data, bytes) and composite_policy is not None else data
        allowed = eligible_composite_bones(composite_policy, policy_data, asset, parts)
        parts = group_bone_composites(parts, model['bones'], output, allowed)
    model['parts'] = parts
    return model


def remap_sound_event(event, character, clip, collection='craftmasters'):
    if not isinstance(event, dict) or not isinstance(event.get('sounds'), list) or len(event['sounds']) != 1:
        raise ValueError(f'invalid source sound event: {character}.{clip}')
    sound = event['sounds'][0]
    expected = f'ginko_fantasy_{collection}:{character}/{clip}'
    if not isinstance(sound, dict) or sound.get('name') != expected:
        raise ValueError(f'unexpected source sound: {character}.{clip}')
    pitch = sound.get('pitch', 1)
    if sound.get('type', 'file') != 'file' or type(pitch) not in (int, float) or not math.isfinite(pitch) or pitch <= 0:
        raise ValueError(f'unsupported source sound type or pitch: {character}.{clip}')
    result = dict(event)
    result['sounds'] = [dict(sound, name=f'grounds:npc/{collection}/{character}/{clip}')]
    return result


def archive_members(infos, relative):
    return [i for i in infos if i.filename == relative or i.filename.endswith('/' + relative)]


def vorbis_duration_millis(data):
    """Read bounded single-stream Vorbis timing, not codec samples, at pack build time.

    Ogg pages: RFC3533 section6. Vorbis sample/granule units: Xiph Vorbis I A.2.
    Chaining/multiplexing and damaged/incomplete pages are deliberately rejected.
    """
    offset, sequence, serial, rate, packets, last_granule = 0, 0, None, None, 0, 0
    pending = bytearray()
    continued = False
    ended = False
    while offset < len(data):
        if ended or offset + 27 > len(data) or data[offset:offset + 5] != b'OggS\x00':
            raise ValueError('invalid or incomplete Ogg page')
        flags = data[offset + 5]
        granule, page_serial, page_sequence, checksum = struct.unpack_from('<qIII', data, offset + 6)
        count = data[offset + 26]
        body = offset + 27 + count
        if body > len(data): raise ValueError('incomplete Ogg lacing')
        lacing = data[offset + 27:body]
        end = body + sum(lacing)
        if end > len(data): raise ValueError('incomplete Ogg payload')
        page = bytearray(data[offset:end]); page[22:26] = b'\x00' * 4
        crc = 0
        for byte in page:
            crc ^= byte << 24
            for _ in range(8): crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xffffffff
        if crc != checksum: raise ValueError('Ogg checksum mismatch')
        if flags & ~7 or bool(flags & 1) != continued or page_sequence != sequence:
            raise ValueError('invalid Ogg page sequence or continuation')
        if sequence == 0:
            if flags != 2 or lacing != bytes([30]): raise ValueError('invalid Vorbis beginning')
            serial = page_serial
        elif flags & 2 or page_serial != serial: raise ValueError('chained or multiplexed Ogg is unsupported')
        cursor = body
        for size in lacing:
            if packets < 3:
                pending.extend(data[cursor:cursor + size])
                if len(pending) > 1_048_576: raise ValueError('Vorbis header too large')
            cursor += size
            continued = size == 255
            if not continued:
                if packets < 3:
                    expected = bytes([1 + packets * 2]) + b'vorbis'
                    if not pending.startswith(expected): raise ValueError('invalid Vorbis header order')
                    if packets == 0:
                        if len(pending) != 30: raise ValueError('invalid Vorbis identification')
                        version, channels, rate = struct.unpack_from('<IBI', pending, 7)
                        small, large = pending[28] & 15, pending[28] >> 4
                        if version != 0 or not channels or not rate or not 6 <= small <= large <= 13 or pending[29] != 1:
                            raise ValueError('invalid Vorbis identification')
                    pending.clear()
                packets += 1
        if granule != -1:
            if granule < last_granule: raise ValueError('invalid Vorbis sample position')
            last_granule = granule
        ended = bool(flags & 4)
        if ended and (granule < 1 or continued or packets < 4): raise ValueError('invalid Vorbis end')
        offset, sequence = end, sequence + 1
    if not ended or not rate or last_granule > rate * 300: raise ValueError('missing or excessive Vorbis duration')
    return (last_granule * 1000 + rate - 1) // rate



def compile_archives(sources, output, geometry_mode='rigid', composite_policy=None):
    if geometry_mode not in ('rigid', 'composite'): raise ValueError('invalid NPC geometry mode')
    if composite_policy is not None and geometry_mode != 'composite': raise ValueError('composite policy requires composite mode')
    sources, output = Path(sources), Path(output)
    archives = [sources / f'Ginko-fantasy_{c}_NPCs.zip' for c in COLLECTIONS]
    if not all(p.is_file() for p in archives): raise ValueError('missing licensed source archives')
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix='.npc-build-', dir=output.parent)); backup = None
    try:
        models, sounds, hashes = [], {}, {}
        for collection, path in zip(COLLECTIONS, archives):
            hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
            with zipfile.ZipFile(path) as archive:
                infos = archive.infolist()
                if len(infos) > 1000 or sum(i.file_size for i in infos) > 200_000_000: raise ValueError('archive too large')
                for i in infos:
                    name = i.filename
                    if '\\' in name or PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts or i.file_size > 30_000_000: raise ValueError(f'unsafe archive path or size: {name}')
                source_sounds = {}
                sound_root = f'ResourcePack/assets/ginko_fantasy_{collection}/'
                sound_manifests = archive_members(infos, sound_root + 'sounds.json') if collection in SOUND_CLIPS else []
                if len(sound_manifests) > 1 or (collection == 'craftmasters' and not sound_manifests):
                    raise ValueError('missing or duplicate source sounds.json')
                if sound_manifests:
                    source_sounds = json.loads(archive.read(sound_manifests[0]))
                    if not isinstance(source_sounds, dict): raise ValueError('invalid source sounds.json')
                prefix = f'ginko_fantasy_{collection}_'
                entries = sorted((i for i in infos if i.filename.endswith('.bbmodel')), key=lambda i: i.filename)
                for info in entries:
                    stem = PurePosixPath(info.filename).stem
                    if not stem.startswith(prefix) or not re.fullmatch('[a-z0-9_]+', stem[len(prefix):]): raise ValueError('invalid model filename')
                    character = stem[len(prefix):]
                    data = archive.read(info)
                    try: model = compile_model(data, collection, character, temp, geometry_mode, composite_policy)
                    except (ValueError, KeyError, TypeError) as e: raise ValueError(f'{info.filename}: {e}') from e
                    for clip in SOUND_CLIPS.get(collection, ()):
                        event_key = f'{character}.{clip}'
                        event = source_sounds.get(event_key)
                        match = archive_members(infos, sound_root + f'sounds/{character}/{clip}.ogg')
                        if len(match) > 1: raise ValueError('duplicate sound')
                        if event_key in source_sounds and not match: raise ValueError('missing source sound')
                        if match:
                            if not sound_manifests or match[0].filename != sound_manifests[0].filename.removesuffix('sounds.json') + f'sounds/{character}/{clip}.ogg':
                                raise ValueError('sound is outside source sounds.json root')
                            if clip not in model['animations']: raise ValueError('sound references missing animation')
                            key = f'npc/{collection}/{character}/{clip}'; target = temp / f'pack/assets/grounds/sounds/{key}.ogg'
                            mapped = remap_sound_event(event, character, clip, collection)
                            audio = archive.read(match[0])
                            raw_duration = vorbis_duration_millis(audio)
                            event_pitch = min(mapped['sounds'][0].get('pitch', 1), 1)
                            if raw_duration > 300_000 * event_pitch: raise ValueError('source sound duration exceeds supported range')
                            duration = math.ceil(raw_duration / event_pitch)
                            if not 1 <= duration <= 300_000: raise ValueError('source sound duration exceeds supported range')
                            target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(audio)
                            sounds[key] = mapped; model['sounds'][clip] = {'key': f'grounds:{key}', 'volume': 0.8, 'pitch': 1.0, 'radius': 12.0, 'durationMillis': duration}
                    models.append(model)
        if len(models) != 15 or len({m['asset'] for m in models}) != 15: raise ValueError('expected 15 unique NPCs')
        if composite_policy is not None and set(composite_policy['models']) != {m['asset'] for m in models}:
            raise ValueError('composite eligibility model set does not match sources')
        models.sort(key=lambda m:m['asset'])
        write_json(temp / 'pack/assets/grounds/sounds.json', sounds)
        index = []
        for m in models:
            suffix = m['asset'].removeprefix('grounds:npc/'); resource = f'/gg/grounds/resourcepacks/npc/{suffix}.json'
            index.append({'asset': m['asset'], 'resource': resource}); write_json(temp / ('catalog-resources' + resource), m)
        write_json(temp / 'catalog-resources/gg/grounds/resourcepacks/npc/index.json', {'schemaVersion': 1, 'models': index})
        kt = ['package gg.grounds.resourcepacks.catalog', '', 'import gg.grounds.scene.format.*', 'import java.util.Collections', '', 'object GroundsNpcModels {', '    val definitions: Map<AssetKey, AssetDefinition> = Collections.unmodifiableMap(linkedMapOf(']
        for m in models:
            key=m['asset']; b=m['bounds']; v=lambda x: 'Vec3('+', '.join(repr(float(n)) for n in x)+')'
            anim=', '.join('LocalId(' + json.dumps(x) + ')' for x in sorted(m['animations']))
            kt.append(f'        AssetKey("{key}") to AssetDefinition(AssetKey("{key}"), AssetKind.NPC_BODY, setOf({anim}), LocalBounds({v(b["center"])}, {v(b["size"])}), emptyMap()),')
        for key in sorted(sounds): kt.append(f'        AssetKey("grounds:{key}") to AssetDefinition(AssetKey("grounds:{key}"), AssetKind.SOUND, emptySet(), null, emptyMap()),')
        kt += ['    ))', '    val modelResources: Map<AssetKey, String> = Collections.unmodifiableMap(linkedMapOf(']
        kt += [f'        AssetKey("{i["asset"]}") to "{i["resource"]}",' for i in index]
        kt += ['    ))', '}', '']
        kp=temp/'catalog-kotlin/gg/grounds/resourcepacks/catalog/GroundsNpcModels.kt'; kp.parent.mkdir(parents=True,exist_ok=True); kp.write_text('\n'.join(kt),encoding='utf-8')
        files=sorted(str(p.relative_to(temp/'pack')) for p in (temp/'pack').rglob('*') if p.is_file())
        write_json(temp/'catalog-resources/gg/grounds/resourcepacks/npc/pack-digests.json', {p: hashlib.sha256((temp/'pack'/p).read_bytes()).hexdigest() for p in files})
        write_json(temp/'bundle.json', {'partModelPaths':[p for p in files if p.startswith('assets/grounds/models/npc_parts/')], 'files': files, 'sourceSha256':hashes, 'models':[m['asset'] for m in models]})
        package_content(temp)
        if output.exists():
            backup = Path(tempfile.mkdtemp(prefix='.npc-old-',dir=output.parent)); backup.rmdir(); os.replace(output, backup)
        try: os.replace(temp, output)
        except BaseException:
            if backup: os.replace(backup, output); backup = None
            raise
        if backup: shutil.rmtree(backup)
    finally:
        if temp.exists(): shutil.rmtree(temp)


def compile_empty(output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix='.npc-empty-', dir=output.parent))
    try:
        kt = temp / 'catalog-kotlin/gg/grounds/resourcepacks/catalog/GroundsNpcModels.kt'
        kt.parent.mkdir(parents=True, exist_ok=True)
        kt.write_text('package gg.grounds.resourcepacks.catalog\n\nimport gg.grounds.scene.format.*\nimport java.util.Collections\n\nobject GroundsNpcModels {\n    val definitions: Map<AssetKey, AssetDefinition> = Collections.unmodifiableMap(linkedMapOf())\n    val modelResources: Map<AssetKey, String> = Collections.unmodifiableMap(linkedMapOf())\n}\n', encoding='utf-8')
        write_json(temp / 'catalog-resources/gg/grounds/resourcepacks/npc/index.json', {'schemaVersion': 1, 'models': []})
        write_json(temp / 'catalog-resources/gg/grounds/resourcepacks/npc/pack-digests.json', {})
        (temp / 'pack').mkdir()
        write_json(temp / 'bundle.json', {'partModelPaths': [], 'files': [], 'sourceSha256': {}, 'models': []})
        backup = None
        if output.exists():
            backup = Path(tempfile.mkdtemp(prefix='.npc-old-', dir=output.parent)); backup.rmdir(); os.replace(output, backup)
        try: os.replace(temp, output)
        except BaseException:
            if backup: os.replace(backup, output)
            raise
        if backup: shutil.rmtree(backup)
    finally:
        if temp.exists(): shutil.rmtree(temp)


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--sources', type=Path); parser.add_argument('--output', required=True, type=Path); parser.add_argument('--geometry-mode', choices=('rigid', 'composite'), default='rigid'); parser.add_argument('--composite-policy', type=Path); parser.add_argument('--importer-classpath'); args=parser.parse_args()
    if args.importer_classpath: os.environ['NPC_IMPORTER_CLASSPATH'] = args.importer_classpath
    try:
        policy = None
        if args.composite_policy:
            if args.sources is None or args.geometry_mode != 'composite': raise ValueError('composite policy requires licensed sources and composite mode')
            if args.composite_policy.stat().st_size > 1_048_576: raise ValueError('composite policy too large')
            policy = json.loads(args.composite_policy.read_bytes())
            if not isinstance(policy, dict): raise ValueError('composite policy must be an object')
        if args.sources is None: compile_empty(args.output)
        else: compile_archives(args.sources, args.output, args.geometry_mode, policy)
    except (ValueError, OSError, zipfile.BadZipFile) as e: parser.exit(1, f'NPC compilation failed: {e}\n')

if __name__ == '__main__': main()
