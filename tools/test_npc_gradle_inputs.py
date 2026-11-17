"""Behavioral coverage for the actual product Gradle task in an independent checkout copy."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from test_compile_npc_models import source_archives


class GradleImporterInputsTest(unittest.TestCase):
    def test_classpath_reorder_rebuilds_content_and_rechecks_compiler_identity(self):
        repository = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix='grounds-npc-inputs-') as temporary:
            root = Path(temporary)
            checkout = root / 'producer'; checkout.mkdir()
            tracked = subprocess.check_output(['git', '-C', str(repository), 'ls-files', '-z']).decode().split('\0')
            for name in filter(None, tracked):
                source = repository / name
                if not source.is_file(): continue
                target = checkout / name; target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            sources = root / 'sources'; source_archives(sources)
            extra = root / 'additional-tool.jar'
            with zipfile.ZipFile(extra, 'w') as archive: archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
            original = os.environ['NPC_IMPORTER_CLASSPATH']
            identities = []
            for index, classpath in enumerate((original + os.pathsep + str(extra), str(extra) + os.pathsep + original)):
                log = root / f'build-{index}.log'
                with log.open('w') as output:
                    result = subprocess.run([str(checkout / 'gradlew'), '--offline', '--no-build-cache', 'compileNpcModels',
                        '-PnpcSourceDirectory=' + str(sources), '-PnpcImporterClasspath=' + classpath], cwd=checkout,
                        stdout=output, stderr=subprocess.STDOUT, timeout=180)
                self.assertEqual(0, result.returncode, log.read_text()[-4000:])
                with zipfile.ZipFile(checkout / 'build/generated/npc-models/server-content.zip') as archive:
                    identities.append(json.loads(archive.read('bundle.json'))['contentId'])
            self.assertNotEqual(identities[0], identities[1], 'Changed importer order reused content qualified for the old compiler identity')
