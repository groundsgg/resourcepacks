# resourcepacks

This repository composes the immutable Grounds PackSet: `grounds-content` followed by the higher-priority `grounds-platform`, a canonical manifest, and the JVM catalog `gg.grounds:resourcepacks-catalog`.

## Licensing

Native queue font subsets retain their original rights holders and attribution;
see the product `queue-source/NOTICE.txt` and `provenance.json`. They are excluded
from the first-party artwork claim and the product code licence.

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

## Resolution client

JVM consumers can follow a mutable channel with the existing source form:

```kotlin
PackSetSource(URI("https://cdn.grounds.gg"), "grounds-global", PackSetChannel.EDGE)
```

Or they can pin resolution to one immutable release manifest:

```kotlin
PackSetSource.release(URI("https://cdn.grounds.gg"), "grounds-global", "v0.6.0")
```

The resulting snapshot exposes the resolved `target` and validated `publication`. The older
channel-only source getters remain deprecated compatibility APIs; new code should use the explicit
selection and request URI instead. After a release pin has been validated, its source-bound cache
can be revalidated offline on restart and it has no periodic network poll; an explicit refresh
still completes normally. Channel sources retain their normal refresh behavior.

Using these client sources does not mutate Config Service or activate plugin/config/portal
integration.

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

## Welcome link icons

The platform pack includes `grounds:welcome`: Discord (U+E400), TikTok (U+E401) and a shopping bag (U+E402). The 14px hard-edged atlas is generated deterministically by `WelcomeFont`; source silhouettes come from Simple Icons and Lucide, with their license notices included at `assets/grounds/licenses/welcome-icons.txt`. Callers render the multicolor glyphs white and verify pack readiness before use.

The welcome font also provides the original full Grounds logo with floating stones and its gold base as two cells U+E403/U+E404 (42px height, 41px ascent). The separate asset retains the original proportions and colours. Existing tab/sidebar wordmarks are unchanged.

Welcome logo compatibility: each native bitmap cell must fit the client's 256x256 font atlas. The original 512x241 bitmap uses two 256x241 cells. U+E405 removes the rounded native spacing between cells, joining them seamlessly at 42px GUI height. Separate greeting and social rows keep all ten chat rows visible. Validate against the actual Minecraft client before publication:

```sh
resourcepacks-product/src/test/scripts/verify-welcome-atlas.sh /path/to/minecraft-client.jar art/platform/welcome/logo.png 2
```

## Connection icons

The platform pack adds first-party pin, proxy and server icons in `grounds:connection` (U+E410–U+E412). Render the hard-alpha 10px atlas white after verified compatible pack loading.

The welcome icon baseline uses ascent 12 at height 14, keeping the full bitmap above the adjacent voice line. The personalized greeting retains the player name and the block ends with a blank footer row.

## Escape menu

The platform pack replaces the vanilla pause-menu title with the Grounds wordmark
using a dedicated U+E600 glyph (26px height, 21px ascent). Its additive default-font
provider preserves the normal and forced-Unicode fonts. The existing, hash-pinned
tab wordmark texture is shared; the tab font itself keeps its existing metrics.

All 143 Minecraft 26.3 client languages show the logo. English, German, French,
and Spanish, including their available regional variants, also receive Grounds
button labels:

| Language | Back to game | Disconnect |
| --- | --- | --- |
| English | Back to Grounds | Leave Grounds |
| German | Zurück zu Grounds | Grounds verlassen |
| French | Retour à Grounds | Quitter Grounds |
| Spanish | Volver a Grounds | Salir de Grounds |

These labels follow the Minecraft client language, independently of `/lang`.
Other languages retain their vanilla button translations. The buttons keep their
vanilla actions. Overrides apply while the server pack is loaded and are shared
by every proxy through the same PackSet. Higher-priority client packs can override
these entries. The language list is pinned from asset index 34's `pack.mcmeta`;
building the pack does not read a local Minecraft installation or download assets.
# Queue HUD fonts

The platform contribution includes the private `grounds:queue` font and 25
`grounds:queue_fade_00`–`grounds:queue_fade_24` variants for the compact match queue
action bar. The deterministic 160px rail uses Mini-CD colors and mirrored ends.
No vanilla font or shader is replaced.

Native bitmap atlas subsets, their source hashes and attribution live in
`resourcepacks-product/src/main/resources/gg/grounds/resourcepacks/product/queue-source/`.
Production builds consume these checked-in sources and require no local client.
QueuePackTest pins approved pixel digests, alpha geometry and provider references.
The runtime must approve the resulting platform ZIP's SHA-1 through
`QUEUE_FONT_PACK_SHA1S` before it sends these glyphs (see plugin-match/QUEUE-HUD.md).
