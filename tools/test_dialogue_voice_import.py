import hashlib
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import import_dialogue_assets as importer


def ogg_page(payload, flags, samples, sequence):
    lacing = bytes([255] * (len(payload) // 255) + [len(payload) % 255])
    page = bytearray(
        b'OggS' + bytes([0, flags]) + struct.pack('<qIII', samples, 7, sequence, 0)
        + bytes([len(lacing)]) + lacing + payload
    )
    remainder = int.from_bytes(page, 'big') << 32
    for bit in range(remainder.bit_length() - 1, 31, -1):
        if remainder & (1 << bit):
            remainder ^= 0x104C11DB7 << (bit - 32)
    struct.pack_into('<I', page, 22, remainder)
    return bytes(page)


def vorbis_fixture(samples=48000, rate=48000, channels=1):
    identification = b'\x01vorbis' + struct.pack('<IBIiiiBB', 0, channels, rate, 0, 0, 0, 0xB8, 1)
    return b''.join(ogg_page(payload, flags, granule, sequence) for sequence, (payload, flags, granule) in enumerate([
        (identification, 2, 0),
        (b'\x03vorbis\x00\x00\x00\x00\x00\x00\x00\x00\x01', 0, 0),
        (b'\x05vorbis\x01', 0, 0),
        (b'\x00audio', 4, samples),
    ]))


class DialogueVoiceImportTest(unittest.TestCase):
    names = importer.GUIDE_VOICE_NAMES

    def fixture_for(self, name, **kwargs):
        kwargs.setdefault('samples', 48000 if name in importer.GUIDE_TALK_NAMES else 33600)
        return vorbis_fixture(**kwargs)

    def test_import_maps_all_categorized_mono_vocalizations_without_changing_existing_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            for name in self.names:
                (source / f'{name}.ogg').write_bytes(self.fixture_for(name))
            files = {'assets/dialogue/textures/font/letters.png': b'old-visual-payload'}

            importer.add_guide_voice_assets(files, source)

            event_path = 'assets/dialogue/sounds.json'
            event = json.loads(files[event_path])['guide/speech']
            self.assertEqual(event['sounds'], [
                {'name': f'dialogue:guide/{name}', 'stream': False} for name in importer.GUIDE_TALK_NAMES
            ])
            self.assertEqual(files['assets/dialogue/textures/font/letters.png'], b'old-visual-payload')
            for name in self.names:
                self.assertEqual(files[f'assets/dialogue/sounds/guide/{name}.ogg'], self.fixture_for(name))

    def test_shipped_bank_has_distinct_valid_samples_and_resolvable_event(self):
        source = Path(__file__).resolve().parents[1] / (
            'resourcepacks-dialogue-runtime/src/main/resources/grounds-dialogue/voice'
        )
        files = {}
        importer.add_guide_voice_assets(files, source)
        event = json.loads(files['assets/dialogue/sounds.json'])[importer.GUIDE_VOICE_EVENT]
        self.assertEqual(len(event['sounds']), 10)
        payloads = []
        all_sounds = [sound for category in importer.GUIDE_VOICE_CATEGORIES
                      for sound in json.loads(files['assets/dialogue/sounds.json'])[f'guide/{category}']['sounds']]
        for sound in all_sounds:
            namespace, path = sound['name'].split(':', 1)
            payload = files[f'assets/{namespace}/sounds/{path}.ogg']
            self.assertGreater(len(payload), 1000)
            self.assertFalse(sound['stream'])
            payloads.append(hashlib.sha256(payload).hexdigest())
        self.assertEqual(len(set(payloads)), 14)

    def test_generated_timing_uses_actual_vorbis_granules_for_all_approved_phrases(self):
        source = Path(__file__).resolve().parents[1] / (
            'resourcepacks-dialogue-runtime/src/main/resources/grounds-dialogue/voice'
        )
        files = {}
        importer.add_guide_voice_assets(files, source)
        metadata = json.loads(files[importer.GUIDE_VOICE_METADATA])
        self.assertEqual(metadata['schemaVersion'], 1)
        self.assertEqual(set(metadata['clips']), set(self.names))
        for category, names in importer.GUIDE_VOICE_CATEGORIES.items():
            for name in names:
                clip = metadata['clips'][name]
                self.assertEqual(clip['kind'], category)
                self.assertEqual(clip['durationMillis'], importer.npc_compiler.vorbis_duration_millis(
                    (source / f'{name}.ogg').read_bytes()))
                minimum, maximum = (600, 1800) if category == 'speech' else (450, 1100)
                self.assertTrue(minimum <= clip['durationMillis'] <= maximum)

    def test_category_aliases_use_explicit_namespace_and_mood_duration_bounds(self):
        for samples, valid in ((21600, True), (52800, True), (21552, False), (52848, False)):
            with self.subTest(samples=samples), tempfile.TemporaryDirectory() as temporary:
                source = Path(temporary)
                for name in self.names:
                    fixture = self.fixture_for(name, **({'samples': samples} if name not in importer.GUIDE_TALK_NAMES else {}))
                    (source / f'{name}.ogg').write_bytes(fixture)
                files = {}
                if not valid:
                    with self.assertRaises(ValueError):
                        importer.add_guide_voice_assets(files, source)
                    self.assertEqual(files, {})
                    continue
                importer.add_guide_voice_assets(files, source)
                events = json.loads(files['assets/dialogue/sounds.json'])
                self.assertEqual(len(events), 17)
                for category, names in importer.GUIDE_VOICE_CATEGORIES.items():
                    self.assertEqual(len(events[f'guide/{category}']['sounds']), len(names))
                    for name in names:
                        self.assertEqual(events[f'guide/{category}/{name}']['sounds'], [
                            {'name': f'dialogue:guide/{name}', 'stream': False}
                        ])

    def test_empty_source_is_a_noop_until_approved_voice_is_available(self):
        with tempfile.TemporaryDirectory() as temporary:
            files = {'assets/dialogue/example.json': b'unchanged'}
            importer.add_guide_voice_assets(files, Path(temporary))
            self.assertEqual(files, {'assets/dialogue/example.json': b'unchanged'})

    def test_partial_or_unexpected_source_bank_fails_without_mutating_payloads(self):
        for names in (('bwa',), self.names + ('extra',)):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as temporary:
                source = Path(temporary)
                for name in names:
                    (source / f'{name}.ogg').write_bytes(self.fixture_for(name))
                files = {'assets/dialogue/example.json': b'unchanged'}
                with self.assertRaises(ValueError):
                    importer.add_guide_voice_assets(files, source)
                self.assertEqual(files, {'assets/dialogue/example.json': b'unchanged'})

    def test_stereo_source_is_rejected_without_mutating_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            for name in self.names:
                (source / f'{name}.ogg').write_bytes(self.fixture_for(name, channels=2))
            files = {'assets/dialogue/example.json': b'unchanged'}
            with self.assertRaises(ValueError):
                importer.add_guide_voice_assets(files, source)
            self.assertEqual(files, {'assets/dialogue/example.json': b'unchanged'})

    def test_overlong_syllable_is_rejected_without_mutating_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            for name in self.names:
                (source / f'{name}.ogg').write_bytes(self.fixture_for(name, samples=86448 if name in importer.GUIDE_TALK_NAMES else 52848))
            files = {'assets/dialogue/example.json': b'unchanged'}
            with self.assertRaises(ValueError):
                importer.add_guide_voice_assets(files, source)
            self.assertEqual(files, {'assets/dialogue/example.json': b'unchanged'})

    def test_existing_sample_collision_is_rejected_without_mutating_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            for name in self.names:
                (source / f'{name}.ogg').write_bytes(self.fixture_for(name))
            existing_path = 'assets/dialogue/sounds/guide/bwa.ogg'
            files = {'assets/dialogue/example.json': b'unchanged', existing_path: b'existing'}
            with self.assertRaises(ValueError):
                importer.add_guide_voice_assets(files, source)
            self.assertEqual(files, {'assets/dialogue/example.json': b'unchanged', existing_path: b'existing'})

    def test_import_generation_adds_voice_to_manifest_pin_and_keeps_visual_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / 'dialogue.zip'
            overlay = root / 'overlay'; overlay.mkdir()
            source = root / 'voice'; source.mkdir()
            output = root / 'output'
            for name in self.names:
                (source / f'{name}.ogg').write_bytes(self.fixture_for(name))
            visual_path = 'assets/dialogue/textures/font/letters.png'
            visual_payload = b'unchanged visual fixture'
            effect_path = 'assets/dialogue/post_effect/test.json'
            effect_payload = b'{}'
            files = {visual_path: visual_payload, effect_path: effect_payload}
            source_manifest = {
                'profile': 'test-profile',
                'files': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
                'glyphs': {},
            }
            prefix = 'default-minecraft/test-profile/'
            with zipfile.ZipFile(artifact, 'w') as archive:
                archive.writestr(prefix + 'manifest.json', importer.canonical(source_manifest))
                for name, data in files.items():
                    archive.writestr(prefix + name, data)

            with patch.object(importer, 'PROFILE', 'test-profile'), \
                    patch.object(importer, 'ARTIFACT_SHA256', hashlib.sha256(artifact.read_bytes()).hexdigest()), \
                    patch.object(importer, 'VIGNETTE_CONFIGS', {effect_path}), \
                    patch.object(importer, 'strengthen_vignette', side_effect=lambda _name, payload: payload):
                importer.main(artifact, overlay, output, source)

            generated = output / 'grounds-dialogue/test-profile'
            manifest = json.loads((generated / 'manifest.json').read_bytes())
            self.assertEqual((generated / visual_path).read_bytes(), visual_payload)
            self.assertEqual((generated / effect_path).read_bytes(), effect_payload)
            self.assertEqual(manifest['files'][visual_path], hashlib.sha256(visual_payload).hexdigest())
            self.assertEqual(manifest['pin'], json.loads((generated / 'theme.json').read_bytes())['assetPin'])
            self.assertTrue(all(path in manifest['files'] for path in [
                'assets/dialogue/sounds.json',
                importer.GUIDE_VOICE_METADATA,
                *[f'assets/dialogue/sounds/guide/{name}.ogg' for name in self.names],
            ]))


if __name__ == '__main__':
    unittest.main()
