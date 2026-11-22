"""Import verified release resources, apply frozen Grounds palette, and bind a new asset pin."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ARTIFACT_SHA256 = "caadc23b6a96c2bfbd7bd0718b5618b7f13fe0003a61bc31af2396aa68b5bfdd"
PROFILE = "grounds-26.3"
VIGNETTE_STRENGTH = 0.20
VIGNETTE_CONFIGS = {
    f"assets/dialogue/post_effect/vignette_250_{mode}_{bank}.json"
    for mode in ("enter", "exit")
    for bank in ("a", "b")
}


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


def main(artifact, overlay, output):
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
    manifest["files"] = {name: digest(value) for name, value in files.items()}
    manifest["pin"] = digest(canonical({key: manifest[key] for key in ("profile", "files", "glyphs")}))
    files["manifest.json"] = canonical(manifest)
    files["theme.json"] = canonical({
        "schemaVersion": 1, "profile": PROFILE, "assetPin": manifest["pin"],
        "upstream": "gg.grounds:dialogue-minestom:1.3.0", "upstreamSha256": ARTIFACT_SHA256,
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
