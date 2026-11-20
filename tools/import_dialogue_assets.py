"""Import verified release resources, apply frozen Grounds palette, and bind a new asset pin."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ARTIFACT_SHA256 = "901daad4f1f8280b1e2be90f415af8dc9a520c2f6c509d33c6f6c0c4ef128c12"
PROFILE = "grounds-26.3"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


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
        "upstream": "gg.grounds:dialogue-minestom:1.0.0", "upstreamSha256": ARTIFACT_SHA256,
        "text": "F3F0EA", "secondary": "C1B9AA", "accent": "E7B84B",
    })
    base = Path(output) / "grounds-dialogue" / PROFILE
    for name, value in files.items():
        target = base / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value)


if __name__ == "__main__":
    main(*sys.argv[1:])
