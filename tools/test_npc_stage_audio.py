import base64
import copy
import hashlib
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import compile_npc_models as compiler
from test_compile_npc_models import fixture

def ogg_page(payload, flags, samples, sequence, serial=7):
    lacing = bytes([255] * (len(payload) // 255) + [len(payload) % 255])
    page = bytearray(b'OggS' + bytes([0, flags]) + struct.pack('<qIII', samples, serial, sequence, 0) + bytes([len(lacing)]) + lacing + payload)
    # Fixture encoder uses polynomial long division, independently of the reader.
    remainder = int.from_bytes(page, 'big') << 32
    for bit in range(remainder.bit_length() - 1, 31, -1):
        if remainder & (1 << bit): remainder ^= 0x104C11DB7 << (bit - 32)
    struct.pack_into('<I', page, 22, remainder)
    return bytes(page)


def vorbis_fixture(samples=110401, rate=48000):
    identification = b'\x01vorbis' + struct.pack('<IBIiiiBB', 0, 1, rate, 0, 0, 0, 0xB8, 1)
    return b''.join(ogg_page(payload, flags, granule, seq) for seq, (payload, flags, granule) in enumerate([
        (identification, 2, 0), (b'\x03vorbis\x00\x00\x00\x00\x00\x00\x00\x00\x01', 0, 0),
        (b'\x05vorbis\x01', 0, 0), (b'\x00audio', 4, samples)]))


def sound_archives(sources, wrapped=True, guild_sounds=True):
    """Synthetic vendor archives; never depend on licensed assets in public tests."""
    for collection in ('craftmasters', 'guildfolk', 'townfolk'):
        root = f'Ginko-fantasy_{collection}_NPCs/' if wrapped else ''
        with zipfile.ZipFile(sources / f'Ginko-fantasy_{collection}_NPCs.zip', 'w') as archive:
            events = {}
            for index in range(5):
                character = f'worker_{index}'
                data = fixture()
                data['animations'] = [dict(data['animations'][0], name=clip)
                                      for clip in ('idle2', 'hello', 'wave')]
                archive.writestr(root + f'ModelEngine/blueprints/ginko_fantasy_{collection}_{character}.bbmodel', json.dumps(data))
                if collection == 'guildfolk' and guild_sounds:
                    for clip in ('hello', 'idle2'):
                        events[f'{character}.{clip}'] = {'sounds': [{
                            'name': f'ginko_fantasy_guildfolk:{character}/{clip}',
                            'stream': False, 'attenuation_distance': 12}]}
                        archive.writestr(root + f'ResourcePack/assets/ginko_fantasy_guildfolk/sounds/{character}/{clip}.ogg', vorbis_fixture())
            if collection == 'craftmasters' or events:
                archive.writestr(root + f'ResourcePack/assets/ginko_fantasy_{collection}/sounds.json', json.dumps(events))


class StageAudioRegressionTest(unittest.TestCase):
    def test_authored_slow_pitch_extends_hold_without_double_adjusting_playback(self):
        with tempfile.TemporaryDirectory() as tmp:
            sources = Path(tmp) / 'sources'; sources.mkdir(); sound_archives(sources)
            source = sources / 'Ginko-fantasy_guildfolk_NPCs.zip'
            with zipfile.ZipFile(source) as archive: files = {i.filename: archive.read(i) for i in archive.infolist()}
            path = 'Ginko-fantasy_guildfolk_NPCs/ResourcePack/assets/ginko_fantasy_guildfolk/sounds.json'
            events = json.loads(files[path]); events['worker_0.hello']['sounds'][0]['pitch'] = 0.5
            events['worker_1.hello']['sounds'][0]['pitch'] = 2.0
            files[path] = json.dumps(events).encode()
            with zipfile.ZipFile(source, 'w') as archive:
                for name, data in files.items(): archive.writestr(name, data)
            output = Path(tmp) / 'out'; compiler.compile_archives(sources, output)
            root = output / 'catalog-resources/gg/grounds/resourcepacks/npc/guildfolk'
            slow = json.loads((root / 'worker_0.json').read_text())['sounds']['hello']
            fast = json.loads((root / 'worker_1.json').read_text())['sounds']['hello']
            self.assertEqual(slow['durationMillis'], 4602)
            self.assertEqual(fast['durationMillis'], 2301)
            self.assertEqual(slow['pitch'], 1.0)
            rendered = json.loads((output / 'pack/assets/grounds/sounds.json').read_text())
            self.assertEqual(rendered['npc/guildfolk/worker_0/hello']['sounds'][0]['pitch'], 0.5)


    def test_audio_duration_uses_final_pcm_samples_and_rounds_up_once(self):
        self.assertEqual(compiler.vorbis_duration_millis(vorbis_fixture()), 2301)
        self.assertEqual(compiler.vorbis_duration_millis(vorbis_fixture(samples=48000)), 1000)


    def test_unreliable_audio_duration_fails_closed(self):
        original = vorbis_fixture()
        broken_crc = bytearray(original); broken_crc[-1] ^= 1
        for data in (b'', original[:-1], bytes(broken_crc), original + original,
                     vorbis_fixture(rate=0), vorbis_fixture(samples=-1), vorbis_fixture(samples=48000 * 301),
                     original[:-34] + ogg_page(b'\x00audio', 4, 110401, 3, serial=8)):
            with self.subTest(data=data[:16]), self.assertRaises(ValueError):
                compiler.vorbis_duration_millis(data)


    def test_guildfolk_sounds_import_from_wrapped_and_rootless_archives(self):
        for wrapped in (True, False):
            with self.subTest(wrapped=wrapped), tempfile.TemporaryDirectory() as tmp:
                sources = Path(tmp) / 'sources'; sources.mkdir()
                output = Path(tmp) / 'out'
                sound_archives(sources, wrapped=wrapped)
                compiler.compile_archives(sources, output)
                model = json.loads((output / 'catalog-resources/gg/grounds/resourcepacks/npc/guildfolk/worker_0.json').read_text())
                self.assertEqual(model['sounds'], {
                    'hello': {'key': 'grounds:npc/guildfolk/worker_0/hello', 'volume': 0.8, 'pitch': 1.0, 'radius': 12.0, 'durationMillis': 2301},
                    'idle2': {'key': 'grounds:npc/guildfolk/worker_0/idle2', 'volume': 0.8, 'pitch': 1.0, 'radius': 12.0, 'durationMillis': 2301}})
                sounds = json.loads((output / 'pack/assets/grounds/sounds.json').read_text())
                self.assertEqual(len(sounds), 10)
                self.assertEqual(sounds['npc/guildfolk/worker_0/hello'], {'sounds': [{
                    'name': 'grounds:npc/guildfolk/worker_0/hello', 'stream': False, 'attenuation_distance': 12}]})
                path = 'assets/grounds/sounds/npc/guildfolk/worker_0/hello.ogg'
                self.assertEqual((output / 'pack' / path).read_bytes(), vorbis_fixture())
                digests = json.loads((output / 'catalog-resources/gg/grounds/resourcepacks/npc/pack-digests.json').read_text())
                self.assertEqual(digests[path], hashlib.sha256(vorbis_fixture()).hexdigest())


    def test_previous_guildfolk_archive_without_sounds_remains_supported(self):
        with tempfile.TemporaryDirectory() as tmp:
            sources = Path(tmp) / 'sources'; sources.mkdir()
            output = Path(tmp) / 'out'; sound_archives(sources, guild_sounds=False)
            compiler.compile_archives(sources, output)
            self.assertEqual(json.loads((output / 'pack/assets/grounds/sounds.json').read_text()), {})
            self.assertEqual(len(json.loads((output / 'bundle.json').read_text())['models']), 15)


    def test_invalid_guildfolk_sound_bundle_preserves_previous_output(self):
        for defect in ('duplicate-manifest', 'duplicate-audio', 'missing-audio', 'missing-event', 'wrong-namespace',
                       'wrong-root-audio', 'null-missing-audio', 'missing-animation', 'event-alias', 'invalid-pitch', 'excessive-pitch-duration', 'tiny-pitch'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as tmp:
                sources = Path(tmp) / 'sources'; sources.mkdir(); sound_archives(sources)
                source = sources / 'Ginko-fantasy_guildfolk_NPCs.zip'
                with zipfile.ZipFile(source) as archive:
                    files = {i.filename: archive.read(i) for i in archive.infolist()}
                root = 'Ginko-fantasy_guildfolk_NPCs/ResourcePack/assets/ginko_fantasy_guildfolk/'
                manifest, audio = root + 'sounds.json', root + 'sounds/worker_0/hello.ogg'
                if defect == 'duplicate-manifest': files['duplicate/' + manifest] = files[manifest]
                if defect == 'duplicate-audio': files['duplicate/' + audio] = files[audio]
                if defect == 'missing-audio': del files[audio]
                if defect == 'wrong-root-audio': files['backup/' + audio] = files.pop(audio)
                if defect == 'null-missing-audio':
                    events = json.loads(files[manifest]); events['worker_0.hello'] = None
                    files[manifest] = json.dumps(events).encode(); del files[audio]
                if defect == 'missing-animation':
                    model_path = 'Ginko-fantasy_guildfolk_NPCs/ModelEngine/blueprints/ginko_fantasy_guildfolk_worker_0.bbmodel'
                    data = json.loads(files[model_path])
                    data['animations'] = [clip for clip in data['animations'] if clip['name'] != 'hello']
                    files[model_path] = json.dumps(data).encode()
                if defect in ('event-alias', 'invalid-pitch', 'excessive-pitch-duration', 'tiny-pitch'):
                    events = json.loads(files[manifest]); sound = events['worker_0.hello']['sounds'][0]
                    sound.update({'type': 'event'} if defect == 'event-alias' else {'pitch': 0 if defect == 'invalid-pitch' else (1e-320 if defect == 'tiny-pitch' else 0.001)})
                    files[manifest] = json.dumps(events).encode()
                if defect in ('missing-event', 'wrong-namespace'):
                    events = json.loads(files[manifest])
                    if defect == 'missing-event': del events['worker_0.hello']
                    else: events['worker_0.hello']['sounds'][0]['name'] = 'another:worker_0/hello'
                    files[manifest] = json.dumps(events).encode()
                with zipfile.ZipFile(source, 'w') as archive:
                    for name, data in files.items(): archive.writestr(name, data)
                output = Path(tmp) / 'out'; output.mkdir(); (output / 'sentinel').write_text('previous-pack')
                with self.assertRaises(ValueError): compiler.compile_archives(sources, output)
                self.assertEqual([p.name for p in output.iterdir()], ['sentinel'])
                self.assertEqual((output / 'sentinel').read_text(), 'previous-pack')


    def test_unrelated_townfolk_sound_manifests_remain_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            sources = Path(tmp) / 'sources'; sources.mkdir(); sound_archives(sources)
            with zipfile.ZipFile(sources / 'Ginko-fantasy_townfolk_NPCs.zip', 'a') as archive:
                archive.writestr('ResourcePack/assets/ginko_fantasy_townfolk/sounds.json', 'not-json')
                archive.writestr('backup/ResourcePack/assets/ginko_fantasy_townfolk/sounds.json', '{}')
            output = Path(tmp) / 'out'; compiler.compile_archives(sources, output)
            self.assertEqual(len(json.loads((output / 'bundle.json').read_text())['models']), 15)


    def test_sound_event_preserves_source_attenuation_and_stream(self):
        source = {'sounds': [{'name': 'ginko_fantasy_craftmasters:shipman/wave', 'stream': False, 'attenuation_distance': 12}]}
        self.assertEqual(compiler.remap_sound_event(source, 'shipman', 'wave'),
            {'sounds': [{'name': 'grounds:npc/craftmasters/shipman/wave', 'stream': False, 'attenuation_distance': 12}]})
        with self.assertRaises(ValueError):
            compiler.remap_sound_event({'sounds': [{'name': 'wrong:sound'}]}, 'shipman', 'wave')


