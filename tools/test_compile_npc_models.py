import base64
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import compile_npc_models as compiler


def fixture():
    face = {'uv': [0, 8, 16, 24], 'texture': 0, 'rotation': 90}
    return {'resolution': {'width': 32, 'height': 64},
        'textures': [{'source': 'data:image/png;base64,' + base64.b64encode(b'\x89PNG\r\n\x1a\nfixture').decode()}],
        'elements': [{'uuid': 'cube', 'type': 'cube', 'from': [0, 16, 0], 'to': [4, 24, 2], 'origin': [1, 18, 1], 'rotation': [0, 30, 0], 'faces': {'north': face}}],
        'groups': [{'uuid': 'root', 'name': 'body', 'origin': [0, 16, 0]}, {'uuid': 'child', 'name': 'head', 'origin': [0, 24, 0], 'rotation': [1, 2, 3]}],
        'outliner': [{'uuid': 'root', 'children': [{'uuid': 'child', 'children': ['cube']}]}],
        'animations': [{'name': 'wave', 'loop': 'once', 'length': 1, 'animators': {'child': {'type': 'bone', 'keyframes': [{'channel': 'position', 'time': 0.5, 'interpolation': 'linear', 'data_points': [{'x': '4', 'y': '8', 'z': '-16'}]}]}}}]}


class CompilerTest(unittest.TestCase):
    def test_native_model_textures_are_discoverable_by_vanilla_atlas_directories(self):
        # Minecraft 26.x stitches block/ and item/ directories, not arbitrary texture folders.
        atlas_directories = ('block/', 'item/')
        with tempfile.TemporaryDirectory() as tmp:
            compiler.compile_model(fixture(), 'townfolk', 'builder', Path(tmp))
            model = json.loads((Path(tmp) / 'pack/assets/grounds/models/npc_parts/townfolk/builder/part_000.json').read_text())
            for sprite in model['textures'].values():
                if sprite.startswith('#'): continue
                namespace, path = sprite.split(':', 1)
                self.assertTrue(path.startswith(atlas_directories), f'{sprite} is outside the vanilla atlases')
                self.assertTrue((Path(tmp) / f'pack/assets/{namespace}/textures/{path}.png').is_file())

    def test_native_model_defines_a_resolvable_particle_material(self):
        with tempfile.TemporaryDirectory() as tmp:
            compiler.compile_model(fixture(), 'townfolk', 'builder', Path(tmp))
            model = json.loads((Path(tmp) / 'pack/assets/grounds/models/npc_parts/townfolk/builder/part_000.json').read_text())
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
            model = json.loads((Path(tmp) / 'pack/assets/grounds/models/npc_parts/townfolk/builder/part_000.json').read_text())
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
            self.assertEqual(compiler.compile_model(model, 'townfolk', 'builder', Path(tmp) / 'hidden')['parts'], [])

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
