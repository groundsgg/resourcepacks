# resourcepacks

This repository composes the immutable Grounds PackSet: `grounds-content` followed by the higher-priority `grounds-platform`, a canonical manifest, and the JVM catalog `gg.grounds:resourcepacks-catalog`.

## Licensing

The PackSet product code is licensed under AGPL-3.0-only, except artwork under
[`art/platform/`](art/platform/) and [`art/content/`](art/content/). First-party
platform artwork is all rights reserved. Third-party files under `art/content/`
keep their vendor licences and are not sublicensed. Reusable JVM contracts,
clients, catalogs, and release tooling are Apache-2.0; documentation is MIT.
Provenance for copied platform images is in
[`art/platform/ASSET_ORIGINS.json`](art/platform/ASSET_ORIGINS.json). The
component-specific `LICENSE` file is authoritative when it differs from the
repository-level notice.

## Local build

`version.txt` is the only authoritative ASCII SemVer source. Build a release with explicit provenance; no command infers it from Git or the environment:

```sh
./gradlew :resourcepacks-product:buildPackSet \
  -PpackSetVersion="$(tr -d '\n' < version.txt)" \
  -PprovenanceCommit=<40-lowercase-git-sha> \
  -PpublicationType=release \
  -PpublicationId="v$(tr -d '\n' < version.txt)" \
  -PreleaseOutput=/absolute/absent/output-directory
```

The output directory is created once and contains exactly four regular files:

```text
grounds-content-pack-v<version>.zip
grounds-platform-pack-v<version>.zip
grounds-resourcepack-catalog-v<version>.jar
manifest.json
```

The readable filenames include the release version. `manifest.json` records the SHA-1 and SHA-256
of final bytes, measured sizes, the exact catalog coordinate, fixed pack order, and immutable CDN
URLs. Two fresh builds with identical explicit inputs must compare byte-for-byte.

## Catalog consumers

Publish and consume the catalog as `gg.grounds:resourcepacks-catalog:<packSetVersion>` on JVM 25. Its exact public owner boundary is `GroundsGuiIds`, `GroundsGuiTheme`, `GroundsAssets`, and `GroundsAssetCatalog`; it contains declarations and metadata only, never the ZIP bytes or a runtime delivery client. `GroundsGuiIds` provides the approved theme identifier constants so consumers do not duplicate string literals.

The platform pack uses the approved first-party Grounds brand icon at `art/platform/pack.png` as its visible, square `pack.png`. The artwork is captured through the same held, no-follow source pipeline as the Theme assets and is never reread from an ordinary mutable path during a release build.

## Release automation

Release Please manages conventional versions from the root and creates tags such as `v0.1.0`. Only pushed `v*` tags start publication. A failed tag workflow is retried by rerunning that same tag: Maven, R2, and GitHub Release assets use create-or-compare semantics, so byte-identical existing output is accepted and different output fails.

The production environment provides `R2_BUCKET`, `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, and
`R2_SECRET_ACCESS_KEY` only to the immutable publication job and the separate final
`stable-channel` job. GitHub Packages and Release attachments use the scoped `GITHUB_TOKEN`.

They are published as a schema-v2 PackSet-first release under
`resourcepacks/packsets/grounds-global/releases/v<version>/`: the two readable
`grounds-*-pack-v<version>.zip` files, `grounds-resourcepack-catalog-v<version>.jar`, and
`manifest.json`. Immutable artifacts use `public, max-age=31536000, immutable` and are served
from matching `https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v<version>/…`
URLs. Only `channels/stable.json` is mutable: after Maven, immutable R2 publication, public CDN
verification, and exact GitHub Release assets all succeed, the production-protected final job
conditionally advances it with that run's sequence. Re-running the same tag/run is unchanged;
newer runs can advance it and stale runs fail closed. The public CDN check has no R2 or Cloudflare
credentials.

An exact push to `main` also creates an Edge build. Its version is
`0.0.0-edge.<github.run_number>.g<first-12-commit-hex>`. CI verifies PackSet determinism on every
pull request; Edge waits for that CI run on the same commit before publishing.
The protected `edge` environment alone receives R2 credentials. It validates the previous
`channels/edge.json` pointer and its immutable manifest before reusing a content or platform URL;
reuse requires exact SHA-1, SHA-256, and size equality. Otherwise the changed pack is published
under the current commit root. Catalog and manifest always remain current-commit objects, CDN
bytes are verified, then `channels/edge.json` is conditionally advanced with `github.run_number`.
Missing prior Edge state starts without reuse; malformed or cross-PackSet prior state fails closed.

CI and the release build/publish jobs require the repository's built-in `GITHUB_TOKEN` to have read access to the private `groundsgg` package dependencies. The workflows pass the masked token to Gradle only through `GITHUB_ACTOR` and `GITHUB_TOKEN`; public CDN and release-asset jobs receive no package-resolution token. Pull requests from forks are unsupported unless their token can read the same private organization packages.

## Out of scope

This repository does not activate a PackSet in Config Service, deliver packs from Velocity/Paper/Minestom, operate a portal, introduce mutable CDN aliases, publish reference-pack assets, or implement runtime Scene behavior.
