# Immutable Release Pins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve a canonical immutable release directly and reuse its validated snapshot without periodic HTTP polling, while preserving existing Stable/Edge behavior.

**Architecture:** Add explicit channel/release source selections and resolved-target metadata. Resolve releases from their fixed publication path and persist manifest-only release records through the existing atomic, fail-closed disk-cache machinery. Keep existing generation ownership and asynchronous reconfiguration; do not add another executor, cache framework or online-player integration.

**Tech Stack:** Kotlin/JVM, existing resourcepacks-contract validation, JDK HTTP transport, Gradle, kotlin.test and deterministic scheduler fixtures.

**Spec:** `/home/lukas/grounds/.worktrees/docs-packset-portal-observability/docs/superpowers/specs/2026-09-14-packset-selection-and-rollback-design.md`, Resourcepacks client and Velocity runtime section. This plan implements only the resourcepacks client prerequisite, not the plugin, Config Service, Core, Forge or Portal.

## Global Constraints

- Start from origin/main `08eba354bd19a28f65aea8303643e78c8c105f2c`; original checkout is not the execution workspace.
- Use canonical `v<SemVer>` release IDs, validated through the existing `ChannelTarget(PublicationType.RELEASE, id)` contract, never a separately maintained SemVer regex.
- Keep the current constructor taking `PackSetChannel` for source compatibility and map it to `PackSetSelection.Channel`.
- Channel resolution keeps the current two-request flow and conditional cache behavior.
- Keep the existing channel cache format compatible, including source-key and generation-fingerprint bytes. Pinned snapshots use a distinct source key and manifest-only record, with no invented `channel.json`.
- Release resolution applies the same response limits, URL policy, manifest decoder, publication binding, and pack ordering checks. Never fetch pack ZIPs.
- A validated immutable release is reused on normal refreshes instead of polling every minute. Failure before validation still retries through the existing bounded retry policy.
- Reconfiguration to another source triggers immediate resolution; stale results never replace the newly configured source.
- Invalid or mismatched pinned cache data is discarded and never used as a fallback. A previous healthy snapshot may remain explicitly degraded through existing client semantics, but must not become READY for another selection.
- No new dependencies, broad product changes, live selectors, deployment, release/tag commands or timer/sleep-based tests. Releases are created exclusively by Release Please.
- Use focused implementation subagents on Terra medium; controller owns public API integration and security review. Escalate only for ambiguity, risk or repeated failed delivery.

## API and file map

Create `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetSelection.kt`:

```kotlin
sealed interface PackSetSelection {
    data class Channel(val channel: PackSetChannel) : PackSetSelection
    data class Release(val id: String) : PackSetSelection {
        init { ChannelTarget(PublicationType.RELEASE, id) }
    }
}
sealed interface ResolvedPackSetTarget {
    data class Channel(val document: ChannelDocument) : ResolvedPackSetTarget
    data class Release(val id: String) : ResolvedPackSetTarget
}
```

`PackSetSource` owns `selection: PackSetSelection`, accepts both its existing channel constructor and a new selection constructor. `release(baseUri: URI, packSet: String, id: String): PackSetSource` is a `@JvmStatic` factory. `requestUri: URI` identifies channel JSON or immutable manifest JSON. Preserve `channel: PackSetChannel` and `channelUri: URI` as deprecated channel-only getters with controlled `IllegalStateException` for release sources; do not fabricate a fallback channel. Normal code branches on `selection` and uses `requestUri`.

`PackSetSnapshot` owns `target: ResolvedPackSetTarget`; preserve the existing channel constructor and channel-only getter for old channel consumers. Add `publication: ChannelTarget`, derived from validated manifest publication, for the later Velocity migration. Keep legacy channel fingerprint behavior byte-for-byte (the existing golden test must pass). Release fingerprint binds a domain-separated source selection plus validated manifest bytes; pack ordering and identities are encoded by the validated manifest. Defensive unmodifiable packs remain mandatory.

`ResolverCache` gets additive `releaseId: String? = null`; existing positional channel constructions continue to compile. Release records require no channel bytes/ETag and an exact release ID; internal branches must reject cache-kind/source mismatches rather than reuse them accidentally.

`PackSetDiskCache` retains one implementation of no-follow, atomic write/pointer, fsync and entry validation helpers. Channel generation files remain `channel.json`, `manifest.json`, `metadata.properties`; release generation files are `manifest.json`, `metadata.properties`. Release metadata contains `recordType=release`, `releaseId`, `sourceKey`, generation `fingerprint`, and `manifestSha256`. Define one internal release-cache fingerprint helper used by store/load/validation, binding source key and manifest bytes. Do not change the channel metadata key set/parser expectations. Dispatch expected file sets by source selection in both load and store, including existing-generation validation and staging-entry checks. No duplicated cache class or insecure relaxed entry validation.

## Task 1: Explicit release resolution and persistent cache

**Files:**
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetSelection.kt`
- Modify: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetSource.kt`
- Modify: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetSnapshot.kt`
- Modify: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/RefreshResult.kt`
- Modify: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetResolver.kt`
- Modify: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetDiskCache.kt`
- Test: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetSourceTest.kt`
- Test: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetResolverTest.kt`
- Test: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetDiskCacheTest.kt`

**Interfaces:**
- Consumes existing `PackSetContractJson.decodeManifest`, `ChannelTarget` validation, bounded transport, and `ResolverCache`/`RefreshResult` result-cache mapping.
- Produces source selection/factory/requestUri, snapshot target/publication, and release-aware `PackSetResolver.refresh(cache)`/`revalidate(cache)` with their existing signatures. A successful release cache round trip is independently testable without client scheduling changes.

- [ ] **Step 1: Write focused failing tests before production changes.** Add source validation/key separation and a resolver test using the existing client document fixture. Use these actual test bodies (imports follow neighboring tests):

```kotlin
@Test fun `release directly resolves a manifest without a channel or ZIP request`() {
    val source = PackSetSource.release(URI("https://assets.example.test"), "global", "v1.2.3")
    val bytes = clientDocuments(PackSetSource(source.baseUri, source.packSet, PackSetChannel.STABLE)).manifest
    val transport = LoopbackPackSetServer(mapOf(source.requestUri to LoopbackPackSetServer.response(200, body = bytes)))
    val resolver = PackSetResolver(transport, PackSetClientConfig(source, Path.of("cache")))
    val result = assertIs<RefreshResult.Activated>(resolver.refresh(ResolverCache(null, null, null, null, null)))
    assertEquals(ResolvedPackSetTarget.Release("v1.2.3"), result.snapshot.target)
    assertEquals("v1.2.3", result.snapshot.publication.id)
    assertEquals(listOf("content", "platform"), result.snapshot.packs.map { it.role })
    assertEquals(listOf(source.requestUri), transport.requests.map { it.uri })
    assertIs<RefreshResult.Unchanged>(resolver.refresh(requireNotNull(resolver.cacheOf(result))))
    assertEquals(1, transport.requests.size)
}

@Test fun `release source rejects unsafe IDs and separates cache namespaces`() {
    val base = URI("https://assets.example.test")
    listOf("1.2.3", "v01.2.3", "v1.2.3/other", "v1.2.3%2fother", "../v1.2.3").forEach { id ->
        assertFailsWith<IllegalArgumentException> { PackSetSource.release(base, "global", id) }
    }
    val pin = PackSetSource.release(base, "global", "v1.2.3")
    assertEquals(URI("https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/manifest.json"), pin.requestUri)
    assertNotEquals(PackSetSource(base, "global", PackSetChannel.STABLE).cacheKey, pin.cacheKey)
    assertNotEquals(PackSetSource.release(base, "global", "v1.2.4").cacheKey, pin.cacheKey)
}
```

Add a disk round-trip test: resolve valid bytes as above, store `resolver.cacheOf(result)`, assert `channel.json` is absent in the current generation, load with configured limits, and `resolver.revalidate(loaded)` is Activated with the exact release ID. Mutate metadata `releaseId=v1.2.3` to `releaseId=v1.2.4` and assert load or revalidate fails closed. Restore/store independently, truncate manifest and assert load returns null. Existing source/channel golden key, fingerprint, fsync, pointer and symlink tests remain unchanged.

- [ ] **Step 2: Capture RED.** Run `./gradlew :resourcepacks-client:test --tests '*PackSetSourceTest' --tests '*PackSetResolverTest' --tests '*PackSetDiskCacheTest'`. New API compile failure is a valid first RED; record output honestly.
- [ ] **Step 3: Implement the API/file-map changes, direct release branch and release cache record.** Build release URI only from normalized policy base, validated safe packSet and validated release ID. Decode manifest and require `publication.type == RELEASE && publication.id == selection.id`; reject BUILD manifests and absent/invalid/mismatched releases. Reusable release cache must be rebound to the exact source/selection; do not trust `snapshot != null` alone. A first request uses no conditional ETag and requires 200; a 304 without validated release cache fails. Revalidation creates a snapshot from validated manifest bytes with no channel document. Existing channel branches retain exact integrity/reference validation.
- [ ] **Step 4: Add negative resolver tests from the same fixture.** For selected v1.2.4 with v1.2.3 bytes assert Failed; set `maxManifestBytes=8` and assert Failed; supply a valid cache owned by another pin and assert no Unchanged/READY activation for that cache; serve 304 without cache and assert Failed. Keep redirects/origin validation through existing transport/decoder gates, not a weaker release-specific parser.
- [ ] **Step 5: Run GREEN.** Run the focused command from Step2 and `./gradlew :resourcepacks-client:check`. Confirm old channel golden fingerprint/key tests and disk safety tests still pass; run `git diff --check`.
- [ ] **Step 6: Commit and review.** Stage only the mapped client files and tests; `git commit -m "feat: resolve immutable resource pack releases"`. Independent task review checks publication binding, old cache compatibility, no fabricated metadata and fail-closed persisted bytes before Task2 starts.

## Task 2: Immutable-pin client lifecycle and consumer documentation

**Files:**
- Modify: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetClient.kt`
- Test: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetClientLifecycleTest.kt`
- Test: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/ClientJarBoundaryTest.kt` only if its public class assertions require the additive target types.
- Modify: `README.md` client usage section.

**Interfaces:**
- Consumes Task1 release-aware resolver/cache and `source.selection`.
- Produces the existing `PackSetClient.start()`, `refreshNow(): CompletionStage<RefreshResult>`, and `reconfigure(source): CompletionStage<RefreshResult>` with immutable pins requiring no periodic network poll after READY. Close/state/listener APIs do not change.

- [ ] **Step 1: Write a deterministic failing lifecycle test.** Reuse `DeterministicScheduledExecutor` and existing lifecycle test helpers; construct `PackSetClient(config, transport, scheduler)`, call start and drain the executor as neighboring tests do. Transport serves only the release manifest. Assert READY/source pin, exactly one GET, no scheduled periodic refresh after successful pin activation; call refreshNow, drain and assert Unchanged plus still one GET. Advance scheduler by more than refreshInterval through the fixture API, not real sleeps, and assert no new GET. Then reconfigure to a channel with scripted channel+manifest responses, drain and assert immediate channel READY and normal channel periodic schedule. Adapt fixture method names from the existing scheduler class before writing, not guessed APIs.
- [ ] **Step 2: Capture RED.** Run `./gradlew :resourcepacks-client:test --tests '*PackSetClientLifecycleTest'`; record the failing assertion proving unnecessary pin polling/scheduling.
- [ ] **Step 3: Change only the successful scheduling decision.** In `complete`, cancel retry/periodic handles as before; schedule periodic refresh only when `resultSource.selection is PackSetSelection.Channel`. Keep failure retries and source-generation checks. Startup revalidated release cache must proceed through successful Unchanged reuse rather than network; do not return early outside normal future completion/listener bookkeeping.
- [ ] **Step 4: Verify transitions and restart.** Extend existing fixture tests for channel→pin→different pin→channel, failed pin refresh with retry before first validation, offline pinned restart from Task1 cache (transport throws if touched), corrupt/mismatched pinned cache (no READY), and blocked old source result after reconfiguration (existing latch/generation pattern). Assert state ownership, request counts and terminal public futures, not implementation-only field values. Keep the existing channel offline/degraded lifecycle expectations.
- [ ] **Step 5: Document both usage forms.** Preserve `PackSetSource(baseUri, packSet, PackSetChannel.EDGE)` example and add `PackSetSource.release(URI("https://cdn.grounds.gg"), "grounds-global", "v0.6.0")`. Explain `snapshot.target`/`snapshot.publication`, deprecated channel-only getters, offline revalidated pin cache and no periodic pin polling. State that plugin/config/portal selection rollout remains separate; this release does not activate a live selector.
- [ ] **Step 6: Run final verification and commit.** Run `./gradlew :resourcepacks-client:check`, then `./gradlew check` once for cross-module regressions, plus `git diff --check`. Record exact fresh outputs and test counts. Commit mapped changes as `feat: stop polling validated release pins`.
- [ ] **Step 7: Independent whole-branch review and integration.** Review baseline08eba35..HEAD for public constructor/getter compatibility, stale generation completion, bounded persisted-byte validation and no channel regressions. Fix verified findings in one combined dispatch; rerun affected tests. Push/create PR only after verification. Merge only with all PR CI green and exact tested head, under existing user merge authorization. Release Please alone owns release/version/tag generation. Pin the later Velocity dependency only after the artifact is published.

## Plan self-review

Client-only scope covers explicit selection, direct immutable manifest resolution, genuine target metadata, source-bound fingerprint, old/new cache separation, offline recovery, invalid cache rejection, successful-pin no-poll behavior and reconfiguration. Later plugin target/publication migration is a declared dependent slice, not silently included. No Core availability assumption or live configuration mutation is needed to accept this client PR.
