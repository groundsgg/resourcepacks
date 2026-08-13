# resourcepacks

This repository composes the immutable Grounds PackSet: `grounds-content` followed by the higher-priority `grounds-platform`, a canonical manifest, and the JVM catalog `gg.grounds:resourcepacks-catalog`.

## Local build

`version.txt` is the only authoritative ASCII SemVer source. Build a release with explicit provenance; no command infers it from Git or the environment:

```sh
./gradlew :resourcepacks-product:buildPackSet \
  -PpackSetVersion="$(tr -d '\n' < version.txt)" \
  -PprovenanceCommit=<40-lowercase-git-sha> \
  -PprovenanceTag="v$(tr -d '\n' < version.txt)" \
  -PreleaseOutput=/absolute/absent/output-directory
```

The output directory is created once and contains exactly four regular files:

```text
grounds-content-<sha1>.zip
grounds-platform-<sha1>.zip
grounds-resourcepacks-catalog-<version>.jar
manifest.json
```

The ZIP names include the SHA-1 of their final bytes. `manifest.json` records both hashes, measured sizes, the exact catalog coordinate, fixed pack order, and immutable CDN URLs. Two fresh builds with identical explicit inputs must compare byte-for-byte.

## Catalog consumers

Publish and consume the catalog as `gg.grounds:resourcepacks-catalog:<packSetVersion>` on JVM 25. Its public boundary is `GroundsGuiTheme`, `GroundsAssets`, and `GroundsAssetCatalog`; it contains declarations and metadata only, never the ZIP bytes or a runtime delivery client.

## Release automation

Release Please manages conventional versions from the root and creates tags such as `v0.1.0`. Only pushed `v*` tags start publication. A failed tag workflow is retried by rerunning that same tag: Maven, R2, and GitHub Release assets use create-or-compare semantics, so byte-identical existing output is accepted and different output fails.

The production environment provides only the publication job with `R2_BUCKET`, `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`. GitHub Packages and Release attachments use the scoped `GITHUB_TOKEN`. R2 objects are written without overwrite at:

```text
resourcepacks/content/<sha1>.zip
resourcepacks/platform/<sha1>.zip
```

They are served directly as `https://cdn.grounds.gg/resourcepacks/content/<sha1>.zip` and `https://cdn.grounds.gg/resourcepacks/platform/<sha1>.zip` with `application/zip` and `public, max-age=31536000, immutable`. The public CDN check has no R2 or Cloudflare credentials.

## Out of scope

This repository does not activate a PackSet in Config Service, deliver packs from Velocity/Paper/Minestom, operate a portal, introduce mutable CDN aliases, publish reference-pack assets, or implement runtime Scene behavior.
