import base64
import struct
import zlib
import copy
import json
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

import compile_npc_models as compiler


def png(color=b'\xff\x00\x00\xff'):
    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00' + color)) + chunk(b'IEND', b'')


def fixture():
    face = {'uv': [0, 8, 16, 24], 'texture': 0, 'rotation': 90}
    return {'meta': {'format_version': '5.0', 'model_format': 'free'}, 'resolution': {'width': 32, 'height': 64},
        'textures': [{'source': 'data:image/png;base64,' + base64.b64encode(png()).decode()}],
        'elements': [{'uuid': 'cube', 'type': 'cube', 'from': [0, 16, 0], 'to': [4, 24, 2], 'origin': [1, 18, 1], 'rotation': [0, 30, 0], 'faces': {'north': face}}],
        'groups': [{'uuid': 'root', 'name': 'body', 'origin': [0, 16, 0]}, {'uuid': 'child', 'name': 'head', 'origin': [0, 24, 0], 'rotation': [1, 2, 3]}],
        'outliner': [{'uuid': 'root', 'children': [{'uuid': 'child', 'children': ['cube']}]}],
        'animations': [{'name': 'wave', 'loop': 'once', 'length': 1, 'animators': {'child': {'type': 'bone', 'keyframes': [{'channel': 'position', 'time': 0.5, 'interpolation': 'linear', 'data_points': [{'x': '4', 'y': '8', 'z': '-16'}]}]}}}]}


def native_part(output, part):
    suffix = part['model'].split(':', 1)[1] + '.json'
    return json.loads((Path(output) / 'pack/assets/grounds/models' / suffix).read_text())


def source_archives(root, first=None):
    root.mkdir()
    for collection in compiler.COLLECTIONS:
        with zipfile.ZipFile(root / f'Ginko-fantasy_{collection}_NPCs.zip', 'w') as archive:
            if collection == 'craftmasters':
                archive.writestr('pack/ResourcePack/assets/ginko_fantasy_craftmasters/sounds.json', '{}')
            for i, character in enumerate(('alpha', 'beta', 'gamma', 'delta', 'epsilon')):
                data = first if collection == 'craftmasters' and i == 0 and first is not None else json.dumps(fixture()).encode()
                archive.writestr(f'ginko_fantasy_{collection}_{character}.bbmodel', data)


class CompilerTest(unittest.TestCase):
    def test_original_archive_member_bytes_reach_the_shared_utf8_json_parser(self):
        original = json.dumps(fixture())
        duplicate = original.replace('"resolution":', '"resolution":{},"resolution":', 1).encode()
        for i, raw in enumerate((duplicate, original.encode('utf-16'))):
            with self.subTest(i=i), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                source_archives(root / 'sources', raw)
                with self.assertRaises(ValueError):
                    compiler.compile_archives(root / 'sources', root / 'out')

    def test_shared_importer_validates_actual_png_payload_instead_of_only_its_signature(self):
        data = fixture()
        data['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(b'\x89PNG\r\n\x1a\nbroken').decode()
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, 'PNG|image|Image'):
                compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))

    def test_explicit_bundle_is_accepted_by_the_runtime_loader_and_detects_model_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model = compiler.compile_model(fixture(), 'townfolk', 'builder', root)
            compiler.write_json(root / 'catalog-resources/gg/grounds/resourcepacks/npc/townfolk/builder.json', model)
            compiler.write_json(root / 'catalog-resources/gg/grounds/resourcepacks/npc/index.json', {
                'schemaVersion': 1, 'models': [{'asset': model['asset'], 'resource': '/gg/grounds/resourcepacks/npc/townfolk/builder.json'}]})
            compiler.write_json(root / 'pack/assets/grounds/sounds.json', {})
            compiler.package_content(root)
            self.assertTrue((root / 'server-content.zip').is_file())
            compiler.validate_content(root / 'server-content.zip', root / 'resourcepack.zip')
            with zipfile.ZipFile(root / 'server-content.zip') as archive:
                entries = {name: archive.read(name) for name in archive.namelist()}
            resource = next(name for name in entries if name != 'bundle.json')
            entries[resource] += b' '
            with zipfile.ZipFile(root / 'server-content.zip', 'w') as archive:
                for name, data in entries.items(): archive.writestr(name, data)
            with self.assertRaisesRegex(ValueError, 'HASH|contract|differ'):
                compiler.validate_content(root / 'server-content.zip', root / 'resourcepack.zip')

    def test_legacy_global_pack_cannot_replace_grouped_meshes_or_materials_in_either_order(self):
        data = fixture()
        data['elements'][0]['rotation'] = [0, 0, 0]
        second = copy.deepcopy(data['elements'][0])
        second.update(uuid='second', **{'from': [8, 16, 0], 'to': [12, 24, 2]})
        data['elements'].append(second)
        data['outliner'][0]['children'][0]['children'].append('second')
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))
            generated = {str(p.relative_to(Path(tmp) / 'pack')): p.read_bytes()
                         for p in (Path(tmp) / 'pack').rglob('*') if p.is_file()}
            legacy_model = 'grounds:npc_parts/townfolk/builder/part_000'
            legacy = {
                'assets/grounds/items/npc_parts/townfolk/builder/part_000.json': json.dumps({'model': {'type': 'minecraft:model', 'model': legacy_model}}).encode(),
                'assets/grounds/models/npc_parts/townfolk/builder/part_000.json': json.dumps({'elements': [{}]}).encode(),
                'assets/grounds/textures/item/npc/townfolk/builder/texture_000.png': b'old-png',
            }
            for packs in [(legacy, generated), (generated, legacy)]:
                effective = dict(packs[0]); effective.update(packs[1])
                for part in result['parts']:
                    suffix = part['model'].split(':', 1)[1] + '.json'
                    item = json.loads(effective['assets/grounds/items/' + suffix])
                    native_suffix = item['model']['model'].split(':', 1)[1] + '.json'
                    native = json.loads(effective['assets/grounds/models/' + native_suffix])
                    self.assertEqual(len(native['elements']), 2, 'legacy single cube replaced the grouped mesh')
                    sprite = native['textures']['0'].split(':', 1)[1]
                    expected_png = base64.b64decode(data['textures'][0]['source'].split(',', 1)[1])
                    self.assertEqual(effective['assets/grounds/textures/' + sprite + '.png'], expected_png)

    def test_mesh_and_material_changes_receive_new_resource_identities(self):
        with tempfile.TemporaryDirectory() as tmp:
            original = fixture()
            changed_mesh = copy.deepcopy(original)
            changed_mesh['elements'][0]['to'][0] += 2
            changed_texture = copy.deepcopy(original)
            changed_png = png(b'\x00\xff\x00\xff')
            changed_texture['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(changed_png).decode()
            variants = [compiler.compile_model(data, 'townfolk', 'builder', Path(tmp) / str(i))
                        for i, data in enumerate([original, changed_mesh, changed_texture])]
            self.assertEqual(len({v['parts'][0]['model'] for v in variants}), 3)
            for i, model in enumerate(variants):
                key = model['parts'][0]['model']
                path = Path(tmp) / str(i) / ('pack/assets/grounds/models/' + key.split(':', 1)[1] + '.json')
                self.assertTrue(key.endswith(hashlib.sha256(path.read_bytes()).hexdigest()))

    def test_rigid_cubes_share_one_bone_display_without_moving_geometry(self):
        data = fixture()
        first = data['elements'][0]
        first['rotation'] = [0, 0, 0]
        second = copy.deepcopy(first)
        second.update(uuid='second', **{'from': [8, 16, 0], 'to': [12, 24, 2], 'origin': [9, 18, 1]})
        data['elements'].append(second)
        data['outliner'][0]['children'][0]['children'].append('second')
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))
            self.assertEqual(len(result['parts']), 1)
            self.assertEqual(result['parts'][0]['center'], [0.375, 1.25, 0.0625])
            native = native_part(tmp, result['parts'][0])
            self.assertEqual([e['from'] for e in native['elements']], [[2, 4, 7], [10, 4, 7]])
            self.assertEqual([e['to'] for e in native['elements']], [[6, 12, 9], [14, 12, 9]])
            self.assertEqual(len(list((Path(tmp) / 'pack/assets/grounds/models').rglob('*.json'))), 1)

    def test_differently_rotated_cubes_keep_independent_displays(self):
        data = fixture()
        second = copy.deepcopy(data['elements'][0])
        second.update(uuid='second', rotation=[0, 45, 0])
        data['elements'].append(second)
        data['outliner'][0]['children'][0]['children'].append('second')
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))
            self.assertEqual(len(result['parts']), 2)

    def test_mirrored_rigid_cubes_keep_their_authored_side_and_uvs(self):
        data = fixture()
        first = data['elements'][0]
        first.update(rotation=[0, 0, 0], **{'from': [4, 24, 2], 'to': [0, 16, 0]})
        second = copy.deepcopy(first)
        second.update(uuid='second', **{'from': [12, 24, 2], 'to': [8, 16, 0]})
        data['elements'].append(second)
        data['outliner'][0]['children'][0]['children'].append('second')
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))
            self.assertEqual(len(result['parts']), 1)
            self.assertEqual(result['parts'][0]['scale'], [-1, -1, -1])
            native = native_part(tmp, result['parts'][0])
            self.assertEqual([e['from'] for e in native['elements']], [[10, 4, 7], [2, 4, 7]])
            self.assertEqual(native['elements'][0]['faces']['north']['uv'], [0, 2, 8, 6])

    def test_different_bones_and_rotated_pivots_never_merge(self):
        for different_bone in (False, True):
            data = fixture()
            first = data['elements'][0]
            second = copy.deepcopy(first)
            second.update(uuid='second', origin=[9, 18, 1])
            data['elements'].append(second)
            if different_bone:
                data['outliner'][0]['children'].append('second')
            else:
                data['outliner'][0]['children'][0]['children'].append('second')
            with tempfile.TemporaryDirectory() as tmp:
                result = compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))
                self.assertEqual(len(result['parts']), 2)

    def test_large_rigid_groups_split_before_exceeding_native_model_bounds(self):
        data = fixture()
        first = data['elements'][0]
        first['rotation'] = [0, 0, 0]
        second = copy.deepcopy(first)
        second.update(uuid='second', **{'from': [64, 16, 0], 'to': [68, 24, 2]})
        data['elements'].append(second)
        data['outliner'][0]['children'][0]['children'].append('second')
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(data, 'townfolk', 'builder', Path(tmp))
            self.assertEqual(len(result['parts']), 2)
            for path in Path(tmp).glob('pack/assets/grounds/models/npc_parts/**/*.json'):
                for element in json.loads(path.read_text())['elements']:
                    self.assertTrue(all(-16 <= v <= 32 for key in ('from', 'to') for v in element[key]))

    def test_native_model_textures_are_discoverable_by_vanilla_atlas_directories(self):
        # Minecraft 26.x stitches block/ and item/ directories, not arbitrary texture folders.
        atlas_directories = ('block/', 'item/')
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(fixture(), 'townfolk', 'builder', Path(tmp))
            model = native_part(tmp, result['parts'][0])
            for sprite in model['textures'].values():
                if sprite.startswith('#'): continue
                namespace, path = sprite.split(':', 1)
                self.assertTrue(path.startswith(atlas_directories), f'{sprite} is outside the vanilla atlases')
                self.assertTrue((Path(tmp) / f'pack/assets/{namespace}/textures/{path}.png').is_file())

    def test_native_model_defines_a_resolvable_particle_material(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(fixture(), 'townfolk', 'builder', Path(tmp))
            model = native_part(tmp, result['parts'][0])
            reference = model['textures'].get('particle')
            self.assertIsNotNone(reference, 'Minecraft resolves the particle slot while baking every model')
            self.assertTrue(reference.startswith('#'))
            self.assertIn(reference[1:], model['textures'])

    def test_nested_cube_uv_and_animation(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = compiler.compile_model(fixture(), 'townfolk', 'builder', Path(tmp))
            self.assertEqual(result['bones'][1]['parent'], 'root')
            self.assertEqual(result['parts'][0]['center'], [0.125, 1.25, 0.0625])
            self.assertEqual(result['parts'][0]['pivot'], [0.0625, 1.125, 0.0625])
            self.assertEqual(result['parts'][0]['rotation'], [0, 30, 0])
            self.assertEqual(result['animations']['wave']['tracks']['child']['position'][0]['value'], [0.25, 0.5, -1])
            model = native_part(tmp, result['parts'][0])
            self.assertEqual(model['elements'][0]['from'], [6, 4, 7])
            self.assertEqual(model['elements'][0]['faces']['north']['uv'], [0, 2, 8, 6])
            self.assertEqual(model['elements'][0]['faces']['north']['rotation'], 90)

    def test_inverted_cube_preserves_signed_scale_and_visibility_is_editor_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = fixture()
            model['groups'][1]['visibility'] = False
            model['elements'][0]['from'], model['elements'][0]['to'] = [4, 24, 2], [0, 16, 0]
            result = compiler.compile_model(model, 'townfolk', 'builder', Path(tmp))
            self.assertEqual(result['parts'][0]['scale'], [-1, -1, -1])
            self.assertEqual(result['parts'][0]['center'], [0.125, 1.25, 0.0625])
            model['elements'][0]['to'] = [0, 28, 0]
            result = compiler.compile_model(model, 'townfolk', 'builder', Path(tmp) / 'mixed')
            self.assertEqual(result['parts'][0]['scale'], [-1, 1, -1])
            model['groups'][1]['export'] = False
            with self.assertRaises(ValueError): compiler.compile_model(model, 'townfolk', 'builder', Path(tmp) / 'hidden')

    def test_rejects_unsupported_geometry_and_expression(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = fixture(); model['elements'][0]['type'] = 'mesh'
            with self.assertRaises(ValueError): compiler.compile_model(model, 'townfolk', 'builder', Path(tmp))
            model = fixture(); model['animations'][0]['animators']['child']['keyframes'][0]['data_points'][0]['x'] = 'query.foo'
            with self.assertRaises(ValueError): compiler.compile_model(model, 'townfolk', 'builder', Path(tmp))

    def test_sound_event_preserves_source_attenuation_and_stream(self):
        source = {'sounds': [{'name': 'ginko_fantasy_craftmasters:shipman/wave', 'stream': False, 'attenuation_distance': 12}]}
        self.assertEqual(compiler.remap_sound_event(source, 'shipman', 'wave'),
            {'sounds': [{'name': 'grounds:npc/craftmasters/shipman/wave', 'stream': False, 'attenuation_distance': 12}]})
        with self.assertRaises(ValueError):
            compiler.remap_sound_event({'sounds': [{'name': 'wrong:sound'}]}, 'shipman', 'wave')

    def test_archive_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'src'; src.mkdir()
            for collection in ('craftmasters', 'guildfolk', 'townfolk'):
                with zipfile.ZipFile(src / f'Ginko-fantasy_{collection}_NPCs.zip', 'w') as z:
                    if collection == 'craftmasters': z.writestr('../evil', 'x')
            with self.assertRaises(ValueError): compiler.compile_archives(src, Path(tmp) / 'out')

    def test_model_output_is_deterministic(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / 'a', Path(tmp) / 'b'
            self.assertEqual(compiler.compile_model(fixture(), 'townfolk', 'builder', a), compiler.compile_model(fixture(), 'townfolk', 'builder', b))
            self.assertEqual({str(p.relative_to(a)): p.read_bytes() for p in a.rglob('*') if p.is_file()}, {str(p.relative_to(b)): p.read_bytes() for p in b.rglob('*') if p.is_file()})

if __name__ == '__main__': unittest.main()
