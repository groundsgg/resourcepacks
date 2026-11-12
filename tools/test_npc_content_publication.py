#!/usr/bin/env python3
"""Exercise the real binary/source Gradle gate with original synthetic payloads."""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
try:
    for task, filename in [('jar', 'model.json'), ('jar', 'texture.png'), ('sourcesJar', 'source-model.zip')]:
        with tempfile.TemporaryDirectory(prefix='npc-content-gate-') as temporary:
            directory = pathlib.Path(temporary)
            payload = directory / 'payload' / 'example'
            payload.mkdir(parents=True)
            (payload / filename).write_bytes(b'original synthetic publication-guard payload')
            init = directory / 'inject.gradle'
            source = (directory / 'payload').as_posix()
            init.write_text("gradle.projectsEvaluated {\n"
                            f"  gradle.rootProject.project(':resourcepacks-npc-content').tasks.named('{task}') {{\n"
                            f"    from('{source}')\n"
                            "  }\n}\n")
            result = subprocess.run([str(ROOT / 'gradlew'), ':resourcepacks-npc-content:verifyNeutralNpcPublication',
                                     '--offline', '--init-script', str(init)], cwd=ROOT,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if result.returncode == 0 or 'Purchased NPC resources must not be published' not in result.stdout:
                raise AssertionError(f'{task}/{filename} was not refused by the publication gate:\n' + result.stdout)
finally:
    # Restore normal outputs even when a regression is found.
    subprocess.run([str(ROOT / 'gradlew'), ':resourcepacks-npc-content:test',
                    ':resourcepacks-npc-content:verifyNeutralNpcPublication', '--offline'], cwd=ROOT, check=True)
print('NPC content publication gate rejects JSON/non-JSON binary and source payloads and accepts neutral artifacts')
