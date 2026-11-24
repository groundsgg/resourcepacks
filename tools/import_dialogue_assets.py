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
GUIDE_TALK_NAMES = ("bwa", "li", "bup", "wee", "mwa") + tuple(f"v{index:02}" for index in range(6, 11))
GUIDE_GREETING_NAMES = ("hello01", "hello02")
GUIDE_FAREWELL_NAMES = ("bye01", "bye02")
GUIDE_ENDING_NAMES = ("end01", "end02", "end03")
GUIDE_VOICE_CATEGORIES = {
    "speech": GUIDE_TALK_NAMES,
    "greeting": GUIDE_GREETING_NAMES,
    "farewell": GUIDE_FAREWELL_NAMES,
    "ending": GUIDE_ENDING_NAMES,
}
GUIDE_VOICE_NAMES = GUIDE_TALK_NAMES + GUIDE_GREETING_NAMES + GUIDE_FAREWELL_NAMES + GUIDE_ENDING_NAMES
GUIDE_VOICE_METADATA = "assets/dialogue/guide/voice-bank.json"
GUIDE_VOICE_EVENT = "guide/speech"


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
    """Add the approved fluent mono Guide phrases and actual encoded timing."""
    voice_source = Path(voice_source)
    if not voice_source.exists():
        return
    entries = list(voice_source.iterdir())
    if not entries:
        return
    expected = {f"{name}.ogg" for name in GUIDE_VOICE_NAMES}
    actual = {entry.name for entry in entries if entry.is_file()}
    if actual != expected or len(entries) != len(expected):
        raise ValueError("Guide voice source must contain exactly the seventeen Guide OGG vocalizations")

    additions = {}
    clips = {}
    for name in GUIDE_VOICE_NAMES:
        relative = f"assets/dialogue/sounds/guide/{name}.ogg"
        payload = (voice_source / f"{name}.ogg").read_bytes()
        duration_millis = npc_compiler.vorbis_duration_millis(payload)
        minimum, maximum = (300, 600) if name in GUIDE_ENDING_NAMES else ((600, 1800) if name in GUIDE_TALK_NAMES else (450, 1100))
        if not minimum <= duration_millis <= maximum:
            raise ValueError(f"Guide voice duration is outside {minimum}-{maximum}ms: {name}")
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
        kind = next(category for category, names in GUIDE_VOICE_CATEGORIES.items() if name in names)
        clips[name] = {"kind": kind, "durationMillis": duration_millis}

    sounds_path = "assets/dialogue/sounds.json"
    sounds = json.loads(files[sounds_path]) if sounds_path in files else {}
    for category, names in GUIDE_VOICE_CATEGORIES.items():
        event = f"guide/{category}"
        events = {event: {"sounds": [
            {"name": f"dialogue:guide/{name}", "stream": False} for name in names
        ]}}
        for name in names:
            events[f"{event}/{name}"] = {"sounds": [
                {"name": f"dialogue:guide/{name}", "stream": False}
            ]}
        for event_name, definition in events.items():
            if event_name in sounds:
                raise ValueError(f"Guide voice event already exists: {event_name}")
            sounds[event_name] = definition
    if GUIDE_VOICE_METADATA in files:
        raise ValueError("Guide voice metadata already exists")
    additions[GUIDE_VOICE_METADATA] = canonical({"schemaVersion": 2, "clips": clips})
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
