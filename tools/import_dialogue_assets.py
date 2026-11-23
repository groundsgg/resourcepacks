"""Import verified release resources, apply frozen Grounds palette, and bind a new asset pin."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

import compile_npc_models as npc_compiler

ARTIFACT_SHA256 = "4e6be2e0ca53fc21129a3e9d7ca118362756c7c18b2deb085b36c5b2a89e1a1b"
PROFILE = "grounds-26.3"
VIGNETTE_STRENGTH = 0.20
VIGNETTE_CONFIGS = {
    f"assets/dialogue/post_effect/vignette_250_{mode}_{bank}.json"
    for mode in ("enter", "exit")
    for bank in ("a", "b")
}
GUIDE_VOICE_NAMES = ("bwa", "li", "bup", "wee", "mwa")
GUIDE_VOICE_EVENT = "dialogue/guide/speech"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def strengthen_vignette(name, payload):
    effect = json.loads(payload)
    mode = "exit" if "_exit_" in name else "enter"
    expected = {"DurationTicks": 5, "From": 0, "To": 0.15, "Inverse": 1 if mode == "exit" else 0}
    fade_configs = [
        render_pass["uniforms"]["FadeConfig"]
        for render_pass in effect["passes"]
        if "FadeConfig" in render_pass.get("uniforms", {})
    ]
    if len(fade_configs) != 2:
        raise ValueError(f"Expected two native vignette FadeConfig blocks: {name}")
    for uniforms in fade_configs:
        values = {item["name"]: item["value"] for item in uniforms}
        if values != expected:
            raise ValueError(f"Unexpected upstream native vignette FadeConfig values: {name}")
        for item in uniforms:
            if item["name"] == "To":
                item["value"] = VIGNETTE_STRENGTH
    return canonical(effect)


def add_guide_voice_assets(files, voice_source):
    """Add the approved short mono Guide voice bank, if the source bank is present."""
    voice_source = Path(voice_source)
    if not voice_source.exists():
        return
    entries = list(voice_source.iterdir())
    if not entries:
        return
    expected = {f"{name}.ogg" for name in GUIDE_VOICE_NAMES}
    actual = {entry.name for entry in entries if entry.is_file()}
    if actual != expected or len(entries) != len(expected):
        raise ValueError("Guide voice source must contain exactly the five approved OGG syllables")

    additions = {}
    for name in GUIDE_VOICE_NAMES:
        relative = f"assets/grounds/sounds/dialogue/guide/{name}.ogg"
        payload = (voice_source / f"{name}.ogg").read_bytes()
        duration_millis = npc_compiler.vorbis_duration_millis(payload)
        if not 150 <= duration_millis <= 240:
            raise ValueError(f"Guide voice syllable duration is outside 150-240ms: {name}")
        first_page_segments = payload[26]
        identification = 27 + first_page_segments
        if payload[identification:identification + 7] != b"\x01vorbis":
            raise ValueError(f"Guide voice syllable is missing its Vorbis identification packet: {name}")
        _, channels, _ = struct.unpack_from("<IBI", payload, identification + 7)
        if channels != 1:
            raise ValueError(f"Guide voice syllable must be mono: {name}")
        if relative in files:
            raise ValueError(f"Guide voice asset already exists: {relative}")
        additions[relative] = payload

    sounds_path = "assets/grounds/sounds.json"
    sounds = json.loads(files[sounds_path]) if sounds_path in files else {}
    if GUIDE_VOICE_EVENT in sounds:
        raise ValueError(f"Guide voice event already exists: {GUIDE_VOICE_EVENT}")
    sounds[GUIDE_VOICE_EVENT] = {
        "sounds": [
            {"name": f"grounds:dialogue/guide/{name}", "stream": False}
            for name in GUIDE_VOICE_NAMES
        ],
    }
    additions[sounds_path] = canonical(sounds)
    files.update(additions)


def main(artifact, overlay, output, voice_source=None):
    artifact = Path(artifact)
    if digest(artifact.read_bytes()) != ARTIFACT_SHA256:
        raise ValueError("Dialogue release artifact SHA-256 mismatch")
    prefix = f"default-minecraft/{PROFILE}/"
    with zipfile.ZipFile(artifact) as archive:
        manifest = json.loads(archive.read(prefix + "manifest.json"))
        files = {name: archive.read(prefix + name) for name in manifest["files"]}
    if any(digest(value) != manifest["files"][name] for name, value in files.items()):
        raise ValueError("Dialogue release asset SHA-256 mismatch")
    if not VIGNETTE_CONFIGS.issubset(files):
        raise ValueError("Dialogue release is missing one or more native vignette configurations")
    for name in VIGNETTE_CONFIGS:
        files[name] = strengthen_vignette(name, files[name])
    for texture in sorted(Path(overlay).glob("*.png")):
        name = "assets/dialogue/textures/font/" + texture.name
        if name not in files:
            raise ValueError("Unknown dialogue theme overlay")
        files[name] = texture.read_bytes()
    if voice_source is not None:
        add_guide_voice_assets(files, voice_source)
    manifest["files"] = {name: digest(value) for name, value in files.items()}
    manifest["pin"] = digest(canonical({key: manifest[key] for key in ("profile", "files", "glyphs")}))
    files["manifest.json"] = canonical(manifest)
    files["theme.json"] = canonical({
        "schemaVersion": 1, "profile": PROFILE, "assetPin": manifest["pin"],
        "upstream": "gg.grounds:dialogue-minestom:1.4.0", "upstreamSha256": ARTIFACT_SHA256,
        "vignetteStrength": VIGNETTE_STRENGTH,
        "text": "F3F0EA", "secondary": "C1B9AA", "accent": "E7B84B",
    })
    base = Path(output) / "grounds-dialogue" / PROFILE
    for name, value in files.items():
        target = base / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value)


if __name__ == "__main__":
    main(*sys.argv[1:])
