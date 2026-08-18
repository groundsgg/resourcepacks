# Split Vendor Licensing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the marketplace ban with a split licence: Grounds code stays AGPL/Apache, platform art stays CC-BY-NC-SA-4.0, and `art/content/` may hold vendor files under their original licence.

**Architecture:** Keep first-party platform art closed. Carve vendor files out of AGPL in `resourcepacks-product/LICENSE` and `art/content/LICENSE`. Do not add MCModels PNGs, do not fill `ContentContribution`, and do not introduce a private store.

**Tech Stack:** Repository licence files, README, Kotlin contract tests in `resourcepacks-catalog`.

## Global Constraints

- Do not commit unless the user asks.
- Do not copy MCModels PNG files into the tree.
- Do not put vendor files in `art/platform/`.
- Do not relicense vendor files as AGPL-3.0 or CC-BY-NC-SA-4.0.
- CDN player GET URLs stay as they are.
- `ContentContribution` stays empty until a later asset commit.

---

### Task 1: Rewrite the licence contract tests

**Files:**
- Modify: `resourcepacks-catalog/src/test/kotlin/gg/grounds/resourcepacks/catalog/PackArtworkLicenseContractTest.kt`

**Interfaces:**
- Consumes: existing repo-root file walk helpers in this test class
- Produces: assertions that lock the split-licence wording

- [ ] **Step 1: Rewrite the failing tests for the split model**

Replace the README and content-licence tests so they require vendor carve-out language instead of a public-PackSet ban. Keep the platform first-party allowlist and the empty content image set.

```kotlin
@Test
fun `readme licensing carves vendor content out of agpl and cc`() {
    val readme = Files.readString(root.resolve("README.md"))
    val licensing = readme.substringAfter("## Licensing", missingDelimiterValue = "")
    assertTrue(licensing.isNotBlank(), "README must have a Licensing section")
    assertTrue(licensing.contains("AGPL-3.0-only"))
    assertTrue(licensing.contains("art/content"))
    assertTrue(licensing.contains("vendor", ignoreCase = true))
}

@Test
fun `content license keeps vendor files under the seller licence`() {
    val license = Files.readString(root.resolve("art/content/LICENSE"))
    assertTrue(license.contains("vendor", ignoreCase = true))
    assertTrue(license.contains("MCModels"))
    assertTrue(license.contains("AGPL-3.0").not() || license.contains("not licensed under AGPL") || license.contains("not sublicensed"))
}

@Test
fun `product license carves art content out of agpl`() {
    val license = Files.readString(root.resolve("resourcepacks-product/LICENSE"))
    assertTrue(license.contains("AGPL-3.0"))
    assertTrue(license.contains("art/content"))
}
```

Keep these existing behaviours:

- platform LICENSE names CC-BY-NC-SA, first-party, and that commercial marketplace files do not belong in `art/platform/`
- `ASSET_ORIGINS.json` sources stay under `library-gui/examples/theme-demo/art/`
- repository images stay the eight approved platform PNGs
- `art/content/` contains only `.gitkeep` and `LICENSE`

- [ ] **Step 2: Run tests and confirm the new wording tests fail**

Run: `GRADLE_USER_HOME=/home/lukas/.gradle ./gradlew :resourcepacks-catalog:test --tests gg.grounds.resourcepacks.catalog.PackArtworkLicenseContractTest`

Expected: FAIL on README, `art/content/LICENSE`, and `resourcepacks-product/LICENSE` wording. Platform origin and image allowlist tests still pass.

---

### Task 2: Apply split-licence texts

**Files:**
- Modify: `README.md`
- Modify: `resourcepacks-product/LICENSE`
- Modify: `art/platform/LICENSE`
- Modify: `art/content/LICENSE`

**Interfaces:**
- Consumes: test needles from Task 1 (`art/content`, `vendor`, `AGPL-3.0`, `MCModels`)
- Produces: human-readable split licence that later PNG commits can follow

- [ ] **Step 1: Write the README licensing section**

```markdown
The PackSet product code is licensed under AGPL-3.0-only, except third-party
files under [`art/content/`](art/content/), which keep their vendor licences
and are not sublicensed. Reusable JVM contracts, clients, catalogs, and
release tooling are Apache-2.0; documentation is MIT; platform artwork is
CC-BY-NC-SA-4.0 with provenance in
[`art/platform/ASSET_ORIGINS.json`](art/platform/ASSET_ORIGINS.json). The
component-specific `LICENSE` file is authoritative when it differs from the
repository-level notice.
```

- [ ] **Step 2: Carve vendor files out of the product AGPL notice**

Replace `resourcepacks-product/LICENSE` with:

```text
SPDX-License-Identifier: AGPL-3.0-only

Copyright (C) 2026 Grounds contributors.

This PackSet product component is licensed under the GNU Affero General Public
License, version 3, except third-party artwork under ../art/content/. Those
files remain under the vendor licence in ../art/content/LICENSE and are not
sublicensed under AGPL-3.0. The complete AGPL text is in ../LICENSE.
```

- [ ] **Step 3: Keep platform art first-party CC-only**

In `art/platform/LICENSE`, keep CC-BY-NC-SA-4.0 and first-party provenance. State that commercial marketplace files, including MCModels, must not be copied into this directory; they belong under `art/content/` with the vendor licence.

- [ ] **Step 4: Turn `art/content/LICENSE` into the vendor slot**

```text
Third-party artwork in this directory is not licensed under AGPL-3.0 or
CC-BY-NC-SA-4.0. Vendor files remain under the original seller licence.

Grounds may use them in-game on Grounds-operated servers, including CDN
delivery to Grounds players. Recipients do not receive a right to copy,
modify, or redistribute those files.

MCModels products such as Icons | Vanilla+ Ranks, once added, stay under
the MCModels buyer licence. They are not Grounds original work.
```

- [ ] **Step 5: Re-run the licence contract tests**

Run: `GRADLE_USER_HOME=/home/lukas/.gradle ./gradlew :resourcepacks-catalog:test --tests gg.grounds.resourcepacks.catalog.PackArtworkLicenseContractTest --tests gg.grounds.resourcepacks.catalog.ThemeAssetContractTest :resourcepacks-product:test --tests gg.grounds.resourcepacks.product.PackDefinitionsTest`

Expected: BUILD SUCCESSFUL, 0 failed tests.

---

### Task 3: Record the GitHub visibility step

**Files:** none in git.

Making `groundsgg/resourcepacks` private is a GitHub setting, not a licence file. Attempt:

```sh
gh repo edit groundsgg/resourcepacks --visibility private --accept-visibility-change-consequences
```

If GitHub rejects it (org policy or missing admin), leave the repo public and report the exact error. Do not force-delete or transfer the repository.

---

## Out of scope

- Adding the 38 Vanilla+ Ranks PNGs
- Filling `ContentContribution`
- Changing CDN, R2, or GitHub Release upload jobs
- A private asset store
- Committing (user did not ask)
