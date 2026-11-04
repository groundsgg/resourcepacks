import copy
import hashlib
import itertools
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import compile_npc_models as compiler
from test_compile_npc_models import fixture


def pack_files(root):
    return {str(p.relative_to(root / 'pack')): p.read_bytes()
            for p in (root / 'pack').rglob('*') if p.is_file()}


def rotate(point, degrees):
    x, y, z = point
    a, b, c = map(math.radians, degrees)
    y, z = math.cos(a) * y - math.sin(a) * z, math.sin(a) * y + math.cos(a) * z
    x, z = math.cos(b) * x + math.sin(b) * z, -math.sin(b) * x + math.cos(b) * z
    x, y = math.cos(c) * x - math.sin(c) * y, math.sin(c) * x + math.cos(c) * y
    return [x, y, z]


def two_cubes():
    data = fixture()
    first = data['elements'][0]
    first.update(rotation=[13, 28, -19], origin=[1, 20, -3])
    second = copy.deepcopy(first)
    second.update(uuid='second', rotation=[-12, 37, 23], origin=[-2, 17, 9],
                  **{'from': [8, 15, -3], 'to': [14, 25, 2]})
    data['elements'].append(second)
    data['outliner'][0]['children'][0]['children'].append('second')
    return data


class CompositeCompilerTest(unittest.TestCase):
    def test_explicit_null_policy_fails_before_compilation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            policy = root / 'policy.json'; policy.write_text('null')
            args = ['compile_npc_models.py', '--sources', str(root), '--output', str(root / 'out'),
                    '--geometry-mode', 'composite', '--composite-policy', str(policy)]
            with mock.patch('sys.argv', args), mock.patch.object(compiler, 'compile_archives') as compile_call:
                with self.assertRaises(SystemExit) as failure: compiler.main()
                self.assertEqual(failure.exception.code, 1)
                compile_call.assert_not_called()

    def test_reordered_source_members_cannot_reuse_eligibility(self):
        data = two_cubes()
        data['textures'].append(copy.deepcopy(data['textures'][0]))
        data['elements'][0]['faces']['south'] = dict(data['elements'][0]['faces']['north'], texture=1)
        policy = {'schemaVersion': 1, 'compilerSha256': hashlib.sha256(Path(compiler.__file__).read_bytes()).hexdigest(),
                  'models': {'grounds:npc/townfolk/builder': {'sourceSha256': hashlib.sha256(
                      json.dumps(data, separators=(',', ':'), allow_nan=False).encode()).hexdigest(), 'bones': ['child']}}}
        changed = copy.deepcopy(data)
        changed['elements'][0]['faces'] = dict(reversed(list(changed['elements'][0]['faces'].items())))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError): compiler.compile_model(changed, 'townfolk', 'builder', Path(tmp),
                geometry_mode='composite', composite_policy=policy)

    def test_eligibility_policy_keeps_unapproved_bones_rigid_and_binds_source_and_compiler(self):
        data = two_cubes()
        source_hash = hashlib.sha256(json.dumps(data, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
        policy = {'schemaVersion': 1, 'compilerSha256': hashlib.sha256(Path(compiler.__file__).read_bytes()).hexdigest(),
                  'models': {'grounds:npc/townfolk/builder': {'sourceSha256': source_hash, 'bones': []}}}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            denied = compiler.compile_model(data, 'townfolk', 'builder', root / 'denied', geometry_mode='composite', composite_policy=policy)
            self.assertEqual(len(denied['parts']), 2)
            policy['models']['grounds:npc/townfolk/builder']['bones'] = ['child']
            allowed = compiler.compile_model(data, 'townfolk', 'builder', root / 'allowed', geometry_mode='composite', composite_policy=policy)
            self.assertEqual(len(allowed['parts']), 1)
            policy['models']['grounds:npc/townfolk/builder']['bones'] = ['unknown']
            with self.assertRaises(ValueError): compiler.compile_model(data, 'townfolk', 'builder', root / 'unknown', geometry_mode='composite', composite_policy=policy)
            policy['models']['grounds:npc/townfolk/builder']['bones'] = ['child']
            changed = copy.deepcopy(data); changed['elements'][0]['rotation'][0] += 1
            with self.assertRaises(ValueError): compiler.compile_model(changed, 'townfolk', 'builder', root / 'stale-source', geometry_mode='composite', composite_policy=policy)
            policy['compilerSha256'] = '0' * 64
            with self.assertRaises(ValueError): compiler.compile_model(data, 'townfolk', 'builder', root / 'stale-compiler', geometry_mode='composite', composite_policy=policy)

    def test_compound_anchor_uses_the_center_of_leaf_centers_without_changing_geometry(self):
        data = two_cubes()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = compiler.compile_model(data, 'townfolk', 'builder', root / 'old')
            new = compiler.compile_model(data, 'townfolk', 'builder', root / 'new', geometry_mode='composite')
            centers = []
            for part in old['parts']:
                offset = rotate([a - b for a, b in zip(part['center'], part['pivot'])], part['rotation'])
                centers.append([a + b for a, b in zip(part['pivot'], offset)])
            expected = [(min(c[i] for c in centers) + max(c[i] for c in centers)) / 2 for i in range(3)]
            for a, b in zip(new['parts'][0]['center'], expected): self.assertAlmostEqual(a, b, places=12)
            self.assertEqual(new['parts'][0]['pivot'], new['parts'][0]['center'])

    def test_large_cancelling_pivot_is_composed_before_item_centering(self):
        part = {'pivot': [1e16, 0, 0], 'center': [0, 0, 0], 'rotation': [0, 0, 0]}
        matrix = compiler.composite_part_matrix(part, [0, 0, 0])
        self.assertEqual(matrix, [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1])

    def assert_geometry(self, baseline, candidate, root):
        old = {p['model']: p for p in baseline['parts']}
        origins = {b['id']: b['origin'] for b in candidate['bones']}
        seen = set()
        for part in candidate['parts']:
            item_path = root / ('pack/assets/grounds/items/' + part['model'].split(':')[1] + '.json')
            node = json.loads(item_path.read_text())['model']
            if node['type'] == 'minecraft:model':
                self.assertEqual(part, old[node['model']])
                seen.add(node['model'])
                continue
            origin = origins.get(part['bone'], [0, 0, 0])
            self.assertEqual(part['pivot'], part['center'])
            self.assertEqual(part['rotation'], [0, 0, 0])
            self.assertEqual(part.get('scale', [1, 1, 1]), [1, 1, 1])
            for child in node['models']:
                self.assertNotIn(child['model'], seen)
                seen.add(child['model'])
                source = old[child['model']]
                matrix = child['transformation']
                self.assertEqual(len(matrix), 16)
                self.assertTrue(all(math.isfinite(x) for x in matrix))
                model = json.loads((root / ('pack/assets/grounds/models/' + child['model'].split(':')[1] + '.json')).read_text())
                for cube in model['elements']:
                    for point in itertools.product(*zip(cube['from'], cube['to'])):
                        q = [v / 16 - .5 for v in point]
                        local = [q[i] * source.get('scale', [1, 1, 1])[i]
                                 + source['center'][i] - source['pivot'][i] for i in range(3)]
                        expected = [a + b for a, b in zip(rotate(local, source['rotation']), source['pivot'])]
                        raw = [v / 16 for v in point] + [1]
                        actual = [sum(matrix[row * 4 + j] * raw[j] for j in range(4)) - .5 + part['center'][row]
                                  for row in range(3)]
                        for a, b in zip(expected, actual):
                            self.assertAlmostEqual(a, b, places=11)
        self.assertEqual(seen, set(old))

    def test_rotated_pivoted_cubes_share_one_display_and_retain_all_leaf_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = compiler.compile_model(two_cubes(), 'townfolk', 'builder', root / 'old')
            candidate = compiler.compile_model(two_cubes(), 'townfolk', 'builder', root / 'new', geometry_mode='composite')
            self.assertEqual(len(baseline['parts']), 2)
            self.assertEqual(len(candidate['parts']), 1)
            old_files, new_files = pack_files(root / 'old'), pack_files(root / 'new')
            for name, content in old_files.items():
                self.assertEqual(new_files[name], content, name)
            added = set(new_files) - set(old_files)
            self.assertEqual(len(added), 1)
            self.assertTrue(next(iter(added)).startswith('assets/grounds/items/npc_parts/'))
            self.assertEqual(candidate['bones'], baseline['bones'])
            self.assertEqual(candidate['animations'], baseline['animations'])
            self.assertEqual(candidate['bounds'], baseline['bounds'])
            self.assert_geometry(baseline, candidate, root / 'new')

    def test_mirrored_and_mixed_axis_parts_preserve_geometry_without_rewriting_uvs(self):
        data = two_cubes()
        data['elements'][0].update(**{'from': [4, 24, 2], 'to': [0, 16, 0]})
        data['elements'][1].update(**{'from': [14, 15, 2], 'to': [8, 25, -3]})
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = compiler.compile_model(data, 'townfolk', 'builder', root / 'old')
            new = compiler.compile_model(data, 'townfolk', 'builder', root / 'new', geometry_mode='composite')
            self.assertEqual(len(new['parts']), 1)
            self.assert_geometry(old, new, root / 'new')
            for name, value in pack_files(root / 'old').items():
                self.assertEqual(pack_files(root / 'new')[name], value)

    def test_root_level_parts_compose_relative_to_zero_origin(self):
        data = two_cubes()
        data['outliner'] = ['cube', 'second', {'uuid': 'root', 'children': [{'uuid': 'child', 'children': []}]}]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = compiler.compile_model(data, 'townfolk', 'builder', root / 'old')
            new = compiler.compile_model(data, 'townfolk', 'builder', root / 'new', geometry_mode='composite')
            self.assertEqual(len(new['parts']), 1)
            self.assert_geometry(old, new, root / 'new')

    def test_different_bones_and_singletons_remain_independent(self):
        data = two_cubes()
        data['outliner'][0]['children'][0]['children'].remove('second')
        data['outliner'][0]['children'].append('second')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = compiler.compile_model(data, 'townfolk', 'builder', root / 'old')
            new = compiler.compile_model(data, 'townfolk', 'builder', root / 'new', geometry_mode='composite')
            self.assertEqual(new, old)
            self.assertEqual(pack_files(root / 'new'), pack_files(root / 'old'))

    def test_default_is_rigid_and_composite_identity_changes_with_local_transform(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = two_cubes()
            default = compiler.compile_model(data, 'townfolk', 'builder', root / 'default')
            rigid = compiler.compile_model(data, 'townfolk', 'builder', root / 'rigid', geometry_mode='rigid')
            self.assertEqual(default, rigid)
            self.assertEqual(pack_files(root / 'default'), pack_files(root / 'rigid'))
            first = compiler.compile_model(data, 'townfolk', 'builder', root / 'first', geometry_mode='composite')
            data['elements'][0]['rotation'][0] += 1
            second = compiler.compile_model(data, 'townfolk', 'builder', root / 'second', geometry_mode='composite')
            self.assertNotEqual(first['parts'][0]['model'], second['parts'][0]['model'])
            path = root / 'first' / ('pack/assets/grounds/items/' + first['parts'][0]['model'].split(':')[1] + '.json')
            payload = json.loads(path.read_text())['model']
            canonical = json.dumps(payload, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()
            self.assertTrue(first['parts'][0]['model'].endswith(hashlib.sha256(canonical).hexdigest()))

    def test_float_unrepresentable_transforms_keep_the_existing_rigid_parts(self):
        data = two_cubes()
        for cube in data['elements']:
            cube['origin'] = [1e100, 0, 0]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = compiler.compile_model(data, 'townfolk', 'builder', root / 'old')
            new = compiler.compile_model(data, 'townfolk', 'builder', root / 'new', geometry_mode='composite')
            self.assertEqual(new, old)
            self.assertEqual(pack_files(root / 'new'), pack_files(root / 'old'))


if __name__ == '__main__':
    unittest.main()
