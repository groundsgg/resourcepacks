#!/usr/bin/env python3
"""Compile licensed Blockbench NPC archives into deterministic Grounds resources."""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import zipfile

COLLECTIONS = ('craftmasters', 'guildfolk', 'townfolk')
FACES = {'north', 'south', 'east', 'west', 'up', 'down'}


def number(value):
    if isinstance(value, bool): raise ValueError('boolean is not a number')
    try: value = float(value)
    except (ValueError, TypeError): raise ValueError(f'unsupported numeric expression: {value!r}')
    if not math.isfinite(value): raise ValueError('nonfinite number')
    return value


def vec(value, default=None, divisor=1):
    value = default if value is None else value
    if not isinstance(value, list) or len(value) != 3: raise ValueError(f'invalid vector: {value!r}')
    return [number(x) / divisor for x in value]


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


def compile_model(data, collection, character, output):
    output = Path(output); prefix = f'{collection}/{character}'
    asset = f'grounds:npc/{prefix}'
    elements = {e['uuid']: e for e in data['elements']}
    groups = {g['uuid']: g for g in data['groups']}
    if len(elements) != len(data['elements']) or len(groups) != len(data['groups']): raise ValueError('duplicate UUID')
    textures = data.get('textures', [])
    texture_ids = []
    for i, tex in enumerate(textures):
        source = tex.get('source', '')
        if not source.startswith('data:image/png;base64,'): raise ValueError('texture is not embedded PNG')
        try: png = base64.b64decode(source.split(',', 1)[1], validate=True)
        except Exception as e: raise ValueError('invalid PNG base64') from e
        if not png.startswith(b'\x89PNG\r\n\x1a\n'): raise ValueError('invalid PNG')
        texture_name = f'texture_{i:03d}_{hashlib.sha256(png).hexdigest()}'
        texture_ids.append(f'grounds:item/npc/{prefix}/{texture_name}')
        path = output / f'pack/assets/grounds/textures/item/npc/{prefix}/{texture_name}.png'
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(png)
    bones, parts, hitboxes, seen, bone_ids = [], [], [], set(), set()
    resolution = data['resolution']; uvw, uvh = number(resolution['width']), number(resolution['height'])
    if uvw <= 0 or uvh <= 0: raise ValueError('invalid UV resolution')
    def walk(node, parent=None, hidden=False, hitbox=False):
        if isinstance(node, str):
            if node not in elements or node in seen: raise ValueError(f'unknown or duplicate element {node}')
            seen.add(node); e = elements[node]
            if e.get('export', True) is False or (hidden and not hitbox): return
            if e.get('type', 'cube') != 'cube': raise ValueError(f'unsupported geometry {e.get("type")}')
            lo, hi = vec(e.get('from')), vec(e.get('to'))
            signed_scale = [-1 if a > b else 1 for a,b in zip(lo,hi)]
            lo, hi = [min(a,b) for a,b in zip(lo,hi)], [max(a,b) for a,b in zip(lo,hi)]
            if hitbox:
                hitboxes.append((lo, hi)); return
            inflate = number(e.get('inflate', 0)); lo = [x-inflate for x in lo]; hi = [x+inflate for x in hi]
            size = [b-a for a,b in zip(lo,hi)]
            if any(x > 48 for x in size): raise ValueError('cube too large for native model')
            faces = {}
            for face, f in e.get('faces', {}).items():
                if face not in FACES or not isinstance(f, dict): raise ValueError(f'invalid face {face}')
                tex = f.get('texture')
                if tex is None: continue
                if not isinstance(tex, int) or isinstance(tex, bool) or not 0 <= tex < len(textures): raise ValueError('invalid face texture')
                uv = f.get('uv')
                if not isinstance(uv, list) or len(uv) != 4: raise ValueError('invalid face UV')
                uv = [number(x)*16/(uvw if j % 2 == 0 else uvh) for j,x in enumerate(uv)]
                rotation = f.get('rotation', 0)
                if rotation not in (0,90,180,270): raise ValueError('invalid face rotation')
                faces[face] = {'uv': uv, 'texture': f'#{tex}'}
                if rotation: faces[face]['rotation'] = rotation
            if not faces: raise ValueError('visible cube has no textured faces')
            idx = len(parts); model = f'grounds:npc_parts/{prefix}/part_{idx:03d}'
            native = {'textures': {str(i): t for i,t in enumerate(texture_ids)}, 'elements': [{'from': [8-x/2 for x in size], 'to': [8+x/2 for x in size], 'faces': faces}]}
            native['textures']['particle'] = next(iter(faces.values()))['texture']
            write_json(output / f'pack/assets/grounds/models/npc_parts/{prefix}/part_{idx:03d}.json', native)
            write_json(output / f'pack/assets/grounds/items/npc_parts/{prefix}/part_{idx:03d}.json', {'model': {'type': 'minecraft:model', 'model': model}})
            part = {'bone': parent, 'model': model, 'center': [(a+b)/32 for a,b in zip(lo,hi)], 'pivot': vec(e.get('origin'), [0,0,0], 16), 'rotation': vec(e.get('rotation'), [0,0,0])}
            if signed_scale != [1,1,1]: part['scale'] = signed_scale
            parts.append(part)
            return
        if not isinstance(node, dict) or node.get('uuid') not in groups: raise ValueError('invalid outliner group')
        g = groups[node['uuid']]; gid = g['uuid']
        if gid in bone_ids: raise ValueError('duplicate bone')
        bone_ids.add(gid)
        hitbox = hitbox or g.get('name', '').lower() == 'hitbox'
        hidden = hidden or g.get('export', True) is False
        bones.append({'id': gid, 'name': g['name'], 'parent': parent, 'origin': vec(g.get('origin'), [0,0,0], 16), 'rotation': vec(g.get('rotation'), [0,0,0])})
        for child in node.get('children', []): walk(child, gid, hidden, hitbox)
    for node in data['outliner']: walk(node)
    if set(elements) != seen: raise ValueError('elements missing from outliner')
    animations = {}
    for a in data.get('animations', []):
        name = a['name']
        if name in animations: raise ValueError('duplicate animation')
        loop = a.get('loop', 'once')
        if loop not in ('once', 'loop', 'hold'): raise ValueError('unsupported loop')
        tracks = {}
        for gid, animator in a.get('animators', {}).items():
            frames = animator.get('keyframes', [])
            if not frames: continue
            if gid not in bone_ids or animator.get('type', 'bone') != 'bone' or animator.get('rotation_global') or animator.get('quaternion_interpolation'): raise ValueError(f'unsupported animator {gid}')
            track = {'rotation': [], 'position': [], 'scale': []}
            for k in frames:
                channel = k['channel']; interp = k.get('interpolation', 'linear'); points = k.get('data_points', [])
                if channel not in track or interp not in ('linear','catmullrom','step') or len(points) != 1: raise ValueError('unsupported keyframe')
                p = points[0]; v = [number(p[c]) / (16 if channel == 'position' else 1) for c in 'xyz']
                track[channel].append({'time': number(k['time']), 'value': v, 'interpolation': interp})
            for values in track.values(): values.sort(key=lambda x: x['time'])
            tracks[gid] = track
        animations[name] = {'loop': loop, 'length': number(a['length']), 'tracks': tracks}
    if hitboxes:
        lo = [min(p[0][i] for p in hitboxes) for i in range(3)]; hi = [max(p[1][i] for p in hitboxes) for i in range(3)]
    else:
        lo, hi = [0,0,0], [0,0,0]
    bounds = {'center': [(a+b)/32 for a,b in zip(lo,hi)], 'size': [(b-a)/16 for a,b in zip(lo,hi)]}
    write_json(output / f'pack/assets/grounds/models/npc_bodies/npc/{prefix}.json', {'elements': []})
    return {'schemaVersion': 1, 'asset': asset, 'bounds': bounds, 'bones': bones, 'parts': isolate_part_models(group_rigid_parts(parts, output), output), 'animations': animations, 'sounds': {}}


def remap_sound_event(event, character, clip):
    if not isinstance(event, dict) or not isinstance(event.get('sounds'), list) or len(event['sounds']) != 1:
        raise ValueError(f'invalid source sound event: {character}.{clip}')
    sound = event['sounds'][0]
    expected = f'ginko_fantasy_craftmasters:{character}/{clip}'
    if not isinstance(sound, dict) or sound.get('name') != expected:
        raise ValueError(f'unexpected source sound: {character}.{clip}')
    result = dict(event)
    result['sounds'] = [dict(sound, name=f'grounds:npc/craftmasters/{character}/{clip}')]
    return result


def compile_archives(sources, output):
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
                if collection == 'craftmasters':
                    sound_manifests = [i for i in infos if i.filename.endswith('/ResourcePack/assets/ginko_fantasy_craftmasters/sounds.json')]
                    if len(sound_manifests) != 1: raise ValueError('missing or duplicate source sounds.json')
                    source_sounds = json.loads(archive.read(sound_manifests[0]))
                prefix = f'ginko_fantasy_{collection}_'
                entries = sorted((i for i in infos if i.filename.endswith('.bbmodel')), key=lambda i: i.filename)
                for info in entries:
                    stem = PurePosixPath(info.filename).stem
                    if not stem.startswith(prefix) or not re.fullmatch('[a-z0-9_]+', stem[len(prefix):]): raise ValueError('invalid model filename')
                    character = stem[len(prefix):]
                    try: data = json.loads(archive.read(info))
                    except Exception as e: raise ValueError(f'invalid model {info.filename}: {e}') from e
                    try: model = compile_model(data, collection, character, temp)
                    except (ValueError, KeyError, TypeError) as e: raise ValueError(f'{info.filename}: {e}') from e
                    if collection == 'craftmasters':
                        for clip in ('idle2', 'wave'):
                            match = [i for i in infos if i.filename.endswith(f'/ResourcePack/assets/ginko_fantasy_craftmasters/sounds/{character}/{clip}.ogg')]
                            if len(match) > 1: raise ValueError('duplicate sound')
                            if match:
                                key = f'npc/craftmasters/{character}/{clip}'; target = temp / f'pack/assets/grounds/sounds/{key}.ogg'
                                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(archive.read(match[0]))
                                sounds[key] = remap_sound_event(source_sounds.get(f'{character}.{clip}'), character, clip); model['sounds'][clip] = {'key': f'grounds:{key}', 'volume': 0.8, 'pitch': 1.0, 'radius': 12.0}
                    models.append(model)
        if len(models) != 15 or len({m['asset'] for m in models}) != 15: raise ValueError('expected 15 unique NPCs')
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
    parser=argparse.ArgumentParser(); parser.add_argument('--sources', type=Path); parser.add_argument('--output', required=True, type=Path); args=parser.parse_args()
    try:
        if args.sources is None: compile_empty(args.output)
        else: compile_archives(args.sources, args.output)
    except (ValueError, OSError, zipfile.BadZipFile) as e: parser.exit(1, f'NPC compilation failed: {e}\n')

if __name__ == '__main__': main()
