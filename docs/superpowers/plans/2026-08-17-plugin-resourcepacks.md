# Plugin Resourcepacks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deploy a standalone Velocity plugin that resolves, validates, caches, and sends the configured Grounds PackSet without network I/O on player login.

**Architecture:** `resourcepacks-contract` gains backward-compatible policy-aware validation, and a new published `resourcepacks-client` module owns HTTP resolution, immutable state, and persisted last-known-good data. A new `plugin-resourcepacks` repository adapts that client to Velocity and `plugin-config`; platform-bundle and Stage deployment changes install both plugins on both proxies and seed Stage to the Edge channel.

**Tech Stack:** Kotlin 2.2.20, Java toolchain 25 with JVM 24 bytecode, Gradle 9.5.1, Java `HttpClient`, Jackson 3 inside `resourcepacks-contract`, Velocity/Adventure resource-pack APIs, `plugin-config` 1.0.0, JUnit 5/Kotlin test, GitHub Actions, Helm values, Grounds PlatformBundle.

## Global Constraints

- Preserve the existing no-policy `PackSetContractJson` behavior for `grounds-global` on `https://cdn.grounds.gg`.
- Accept only absolute HTTPS origins with no user info, explicit port, query, or fragment; reject encoded traversal and unsafe path segments.
- The runtime source URL is exactly `{baseUrl}/resourcepacks/packsets/{packSet}/channels/{channel}.json`.
- Keep Jackson and HTTP implementation types out of every public API and published public signature.
- Use Java toolchain 25, Java source/target 24, Kotlin JVM target 24, and committed dependency locks.
- Limit channel bodies to 64 KiB, manifest bodies to 1 MiB, connect timeout to 5 seconds, request timeout to 5 seconds, and default refresh interval to 60 seconds.
- Activate only fully canonical documents with matching origin, PackSet, channel, publication target, SHA-256, SHA-1, size, UUID, role, order, and resource-pack format.
- Persist last-known-good state atomically; never delete caller-owned paths, follow symlinks, or replace a matching-source snapshot with an invalid candidate.
- A source change clears the dispatchable current snapshot until that new source validates; the old source may be reported only as degraded fallback and must never be sent as the new source.
- Player login performs zero config-service/CDN I/O and reads one immutable in-memory snapshot.
- Do not modify `plugin-player`; it remains the player presence/session plugin.
- Do not implement the Minestom adapter or per-gamemode overlays in this slice.
- Never commit credentials, generated caches, release artifacts, or deployment tokens.

---

### Task 1: Make the PackSet contract source-policy aware

**Repository:** `/home/lukas/grounds/resourcepacks`

**Files:**
- Create: `resourcepacks-contract/src/main/kotlin/gg/grounds/resourcepacks/contract/PackSetValidationPolicy.kt`
- Modify: `resourcepacks-contract/src/main/kotlin/gg/grounds/resourcepacks/contract/ChannelDocument.kt`
- Modify: `resourcepacks-contract/src/main/kotlin/gg/grounds/resourcepacks/contract/CanonicalChannelJson.kt`
- Modify: `resourcepacks-contract/src/main/kotlin/gg/grounds/resourcepacks/contract/PackSetContractJson.kt`
- Modify: `resourcepacks-contract/src/test/kotlin/gg/grounds/resourcepacks/contract/ChannelDocumentTest.kt`
- Modify: `resourcepacks-contract/src/test/kotlin/gg/grounds/resourcepacks/contract/ChannelMutationTest.kt`
- Modify: `resourcepacks-contract/src/test/kotlin/gg/grounds/resourcepacks/contract/PackSetContractJsonTest.kt`
- Modify: `resourcepacks-contract/src/test/kotlin/gg/grounds/resourcepacks/contract/ContractJarBoundaryTest.kt`

**Interfaces:**
- Consumes: Existing `PackSetChannel`, `ChannelDocument`, `PackSetManifest`, and default decoder behavior.
- Produces:
  ```kotlin
  data class PackSetValidationPolicy(val baseUri: URI, val packSet: String) {
      companion object { fun groundsDefault(): PackSetValidationPolicy }
      fun channelUri(channel: PackSetChannel): URI
  }

  fun PackSetContractJson.decodeChannel(
      bytes: ByteArray,
      policy: PackSetValidationPolicy,
      expectedChannel: PackSetChannel,
  ): ChannelDecodeResult

  fun PackSetContractJson.decodeManifest(
      bytes: ByteArray,
      policy: PackSetValidationPolicy,
  ): ManifestDecodeResult
  ```

- [ ] **Step 1: Write policy and compatibility tests before production changes**

  Add tests that prove the default overload still rejects `packSet=custom` and an alternate host, while the policy overload accepts canonical documents rooted at `https://assets.example.test/resourcepacks/packsets/custom`. Add exact negative cases for HTTP, user info, explicit ports, a non-root base path, query, fragment, `%2e`, `%2f`, slash, backslash, empty segments, wrong document channel, wrong document PackSet, and a manifest artifact that escapes the configured origin/root.

  ```kotlin
  val policy = PackSetValidationPolicy(URI("https://assets.example.test"), "custom")
  val result = PackSetContractJson.decodeChannel(bytes, policy, PackSetChannel.EDGE)
  assertIs<ChannelDecodeResult.Success>(result)
  assertEquals(
      URI("https://assets.example.test/resourcepacks/packsets/custom/channels/edge.json"),
      policy.channelUri(PackSetChannel.EDGE),
  )
  assertIs<ChannelDecodeResult.Failure>(PackSetContractJson.decodeChannel(bytes))
  ```

- [ ] **Step 2: Run the focused tests and record RED**

  Run:
  ```bash
  ./gradlew --rerun-tasks :resourcepacks-contract:test \
    --tests '*ChannelDocumentTest' \
    --tests '*ChannelMutationTest' \
    --tests '*PackSetContractJsonTest'
  ```
  Expected: compile failure because `PackSetValidationPolicy` and overloads do not exist.

- [ ] **Step 3: Implement the immutable validation policy**

  Validate `baseUri` once, normalize it without a trailing slash, require a safe single `packSet` segment, and build URIs only by appending known literal segments. Keep default convenience construction exact:

  ```kotlin
  companion object {
      private val GROUNDS =
          PackSetValidationPolicy(URI("https://cdn.grounds.gg"), "grounds-global")

      @JvmStatic fun groundsDefault(): PackSetValidationPolicy = GROUNDS
  }
  ```

  Move only host/PackSet layout binding out of model constructors. Keep schema, type/id, sequence, digest, size, channel-target, safe-HTTPS, and canonical-JSON validation mandatory for every path.

- [ ] **Step 4: Thread the policy through both decoders**

  Make old overloads delegate to `groundsDefault()`. Ensure channel decoding compares `document.channel` to `expectedChannel`, and manifest decoding derives release/build roots from the supplied policy. Historical Edge URL acceptance must still be restricted to the same configured policy root.

- [ ] **Step 5: Lock the final JAR boundary**

  Update `ContractJarBoundaryTest` so `PackSetValidationPolicy` is an approved public owner, public descriptors contain only JDK and `gg.grounds.resourcepacks.contract` types, and no Jackson type appears in public descriptors or payload fields.

- [ ] **Step 6: Run focused tests twice and full contract checks**

  Run twice:
  ```bash
  ./gradlew --rerun-tasks :resourcepacks-contract:test \
    --tests '*Channel*Test' \
    --tests '*PackSetContractJsonTest' \
    --tests '*ContractJarBoundaryTest'
  ```
  Then run:
  ```bash
  ./gradlew :resourcepacks-contract:check :verifyDependencyLocks
  git diff --check
  ```
  Expected: all green; the old fixture suite remains unchanged and green.

- [ ] **Step 7: Commit the contract slice**

  ```bash
  git add resourcepacks-contract
  git commit -S -m "feat(contract): support configurable packset sources"
  ```

---

### Task 2: Add the resourcepacks-client public model and source validation

**Repository:** `/home/lukas/grounds/resourcepacks`

**Files:**
- Modify: `settings.gradle.kts`
- Create: `resourcepacks-client/build.gradle.kts`
- Create: `resourcepacks-client/gradle.lockfile`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetSource.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetClientConfig.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetSnapshot.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetClientState.kt`
- Create: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetSourceTest.kt`
- Create: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/ClientJarBoundaryTest.kt`

**Interfaces:**
- Consumes: `PackSetValidationPolicy`, `PackSetChannel`, `ChannelDocument`, `PackSetManifest`.
- Produces:
  ```kotlin
  data class PackSetSource(
      val baseUri: URI,
      val packSet: String,
      val channel: PackSetChannel,
  ) {
      val policy: PackSetValidationPolicy
      val channelUri: URI
      val cacheKey: String
  }

  data class PackSetClientConfig(
      val source: PackSetSource,
      val cacheDirectory: Path,
      val refreshInterval: Duration = Duration.ofSeconds(60),
      val connectTimeout: Duration = Duration.ofSeconds(5),
      val requestTimeout: Duration = Duration.ofSeconds(5),
      val maxChannelBytes: Int = 65_536,
      val maxManifestBytes: Int = 1_048_576,
  )

  data class ResolvedPack(
      val order: Int,
      val role: String,
      val id: String,
      val uuid: UUID,
      val uri: URI,
      val sha1: String,
      val sha256: String,
      val size: Long,
      val required: Boolean,
  )

  data class PackSetSnapshot(
      val source: PackSetSource,
      val channel: ChannelDocument,
      val manifest: PackSetManifest,
      val packs: List<ResolvedPack>,
      val fingerprint: String,
  )

  enum class PackSetClientStatus { STARTING, READY, DEGRADED, UNAVAILABLE, CLOSED }

  data class PackSetClientState(
      val source: PackSetSource,
      val current: PackSetSnapshot?,
      val degradedFallback: PackSetSnapshot?,
      val status: PackSetClientStatus,
      val lastError: String?,
  )
  ```

- [ ] **Step 1: Add the module and failing model tests**

  Include `resourcepacks-client`, depend with `api(project(":resourcepacks-contract"))`, and add JUnit/Kotlin test dependencies only. Test exact defaults and rejected unsafe sources, negative/zero durations and limits, unmodifiable pack snapshots, stable cache keys, and fingerprints changing only when the channel/manifest identity changes.

- [ ] **Step 2: Run the new tests and record RED**

  Run:
  ```bash
  ./gradlew --rerun-tasks :resourcepacks-client:test --tests '*PackSetSourceTest'
  ```
  Expected: compile failure because the client model does not exist.

- [ ] **Step 3: Implement the minimal immutable model**

  Delegate all origin/segment validation to `PackSetValidationPolicy`; do not duplicate regular expressions. Defensive-copy every public collection with `Collections.unmodifiableList(ArrayList(values))`. Compute `cacheKey` as lowercase SHA-256 of `baseUri + "\n" + packSet + "\n" + channel.name.lowercase()`.

- [ ] **Step 4: Add final-JAR API tests**

  Build the real client JAR and inspect it with the Java 25 ClassFile API. Assert that public descriptors expose only JDK, contract, and client types; reject Jackson, Velocity, Minestom, NATS, and `plugin-config` package references.

- [ ] **Step 5: Generate and inspect dependency locks**

  Run:
  ```bash
  ./gradlew :resourcepacks-client:dependencies --write-locks
  ./gradlew :resourcepacks-client:test :verifyDependencyLocks
  git diff --check
  ```
  Expected: `resourcepacks-client/gradle.lockfile` contains the contract dependency and no platform/runtime dependency.

- [ ] **Step 6: Commit the model slice**

  ```bash
  git add settings.gradle.kts resourcepacks-client
  git commit -S -m "feat(client): define packset runtime model"
  ```

---

### Task 3: Implement bounded HTTP resolution and exact document binding

**Repository:** `/home/lukas/grounds/resourcepacks`

**Files:**
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetHttpTransport.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/JdkPackSetHttpTransport.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetResolver.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/RefreshResult.kt`
- Create: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetResolverTest.kt`
- Create: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/LoopbackPackSetServer.kt`

**Interfaces:**
- Consumes: Task 2 model and Task 1 policy-aware decoders.
- Produces:
  ```kotlin
  interface PackSetHttpTransport {
      fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse
  }

  data class PackSetHttpResponse(
      val status: Int,
      val etag: String?,
      val body: InputStream,
  ) : AutoCloseable

  internal data class ResolverCache(
      val channelEtag: String?,
      val channelBytes: ByteArray?,
      val manifestEtag: String?,
      val manifestBytes: ByteArray?,
      val snapshot: PackSetSnapshot?,
  )

  sealed interface RefreshResult {
      data class Activated(val snapshot: PackSetSnapshot, val cache: ResolverCache) : RefreshResult
      data class Unchanged(val snapshot: PackSetSnapshot, val cache: ResolverCache) : RefreshResult
      data class Failed(val reason: String) : RefreshResult
  }

  internal class PackSetResolver(
      private val transport: PackSetHttpTransport,
      private val config: PackSetClientConfig,
  ) {
      fun refresh(cache: ResolverCache): RefreshResult
  }
  ```

- [ ] **Step 1: Write the resolver matrix first**

  Cover canonical Stable and Edge, exact request paths, channel `If-None-Match`, 304 reuse, manifest fetch, manifest SHA-256/size mismatch, wrong publication target, wrong PackSet/channel/origin, malformed/canonical failures, non-200/304 status, connection failure, header timeout, body timeout, channel 64 KiB boundary/+1, manifest 1 MiB boundary/+1, premature EOF, and redirect rejection.

  A candidate is successful only when:
  ```kotlin
  sha256(manifestBytes) == channel.manifest.sha256 &&
      manifestBytes.size.toLong() == channel.manifest.size &&
      manifest.publication.type == channel.target.type &&
      manifest.publication.id == channel.target.id
  ```

- [ ] **Step 2: Run focused RED**

  ```bash
  ./gradlew --rerun-tasks :resourcepacks-client:test --tests '*PackSetResolverTest'
  ```
  Expected: compile failure for missing resolver/transport types.

- [ ] **Step 3: Implement a no-redirect JDK transport and bounded reader**

  Configure `HttpClient.Redirect.NEVER`, a 5-second connect timeout, per-request timeout, and `BodyHandlers.ofInputStream()`. Read through a fixed 64 KiB buffer, count with `Math.addExact`, reject `limit + 1`, close response streams on every path, and never include URI credentials or response bodies in errors.

- [ ] **Step 4: Implement the resolver in validation order**

  Fetch channel, decode with `decodeChannel(bytes, source.policy, source.channel)`, then fetch the exact referenced manifest, verify raw bytes, decode with `decodeManifest(bytes, source.policy)`, bind publication target, and map `manifest.packs.sortedBy(order)` into an unmodifiable `ResolvedPack` list. Fingerprint canonical channel bytes plus manifest bytes with SHA-256.

- [ ] **Step 5: Prove no ZIP download occurs**

  In the loopback fake, fail any request not ending in `/channels/{channel}.json` or `/manifest.json`; assert successful refresh performs exactly two GETs initially and one conditional channel GET on unchanged refresh.

- [ ] **Step 6: Run focused tests twice and client checks**

  ```bash
  ./gradlew --rerun-tasks :resourcepacks-client:test --tests '*PackSetResolverTest'
  ./gradlew --rerun-tasks :resourcepacks-client:test --tests '*PackSetResolverTest'
  ./gradlew :resourcepacks-client:check
  git diff --check
  ```

- [ ] **Step 7: Commit the resolver**

  ```bash
  git add resourcepacks-client
  git commit -S -m "feat(client): resolve validated packsets"
  ```

---

### Task 4: Add atomic cache, refresh lifecycle, and last-known-good behavior

**Repository:** `/home/lukas/grounds/resourcepacks`

**Files:**
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetDiskCache.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetClient.kt`
- Create: `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/RetryPolicy.kt`
- Create: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetDiskCacheTest.kt`
- Create: `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetClientTest.kt`

**Interfaces:**
- Consumes: `PackSetResolver`, `PackSetClientConfig`, `PackSetClientState`.
- Produces:
  ```kotlin
  fun interface PackSetStateListener { fun onState(state: PackSetClientState) }

  class PackSetClient(
      config: PackSetClientConfig,
      transport: PackSetHttpTransport = JdkPackSetHttpTransport(config.connectTimeout),
      scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
  ) : AutoCloseable {
      fun start()
      fun state(): PackSetClientState
      fun refreshNow(): CompletionStage<RefreshResult>
      fun reconfigure(source: PackSetSource): CompletionStage<RefreshResult>
      fun addListener(listener: PackSetStateListener): AutoCloseable
      override fun close()
  }
  ```

- [ ] **Step 1: Write cache and lifecycle tests first**

  Test atomic write/read, truncated metadata, symlinked cache root/file rejection, mismatched source key, raw document revalidation after restart, concurrent `refreshNow()` coalescing, periodic refresh, bounded exponential retry with deterministic injected jitter, unchanged no-notify, changed notify once, source change clearing `current`, old snapshot appearing only in `degradedFallback`, new source activation, and shutdown cancelling tasks/closing listeners.

- [ ] **Step 2: Run focused RED**

  ```bash
  ./gradlew --rerun-tasks :resourcepacks-client:test \
    --tests '*PackSetDiskCacheTest' \
    --tests '*PackSetClientTest'
  ```
  Expected: compile failure for missing cache/client classes.

- [ ] **Step 3: Implement safe persisted cache layout**

  Use immutable generations under `<cacheDirectory>/<source.cacheKey>/generations/<fingerprint>/` containing `channel.json`, `manifest.json`, and `metadata.properties`. Create each generation with `CREATE_NEW`, write/fsync it in a same-parent private staging directory, atomically rename it to its fingerprint, then atomically replace only the plugin-owned `current` pointer file. Never replace or recursively delete a generation directory. Persist ETags and expected byte hashes; re-run the resolver's contract/binding validation before activating recovered bytes.

- [ ] **Step 4: Implement one serialized refresh loop**

  Keep state in `AtomicReference<PackSetClientState>`. Execute fetch/cache work only on the single scheduler. Coalesce concurrent calls onto the current future. `reconfigure` atomically clears the dispatchable current snapshot, records the old snapshot only as fallback, and schedules the new source's refresh immediately. Notify listeners after the atomic state swap, catch listener exceptions individually, and never invoke listeners while holding lifecycle locks.

- [ ] **Step 5: Implement deterministic retry policy**

  Use delays `1s, 2s, 4s, 8s, 16s, 30s` capped at 30 seconds, with injected jitter in `[0.8, 1.2]`. A successful/unchanged refresh resets failures; periodic 60-second refresh resumes afterward.

- [ ] **Step 6: Run focused tests twice and the full repository gate**

  ```bash
  ./gradlew --rerun-tasks :resourcepacks-client:test \
    --tests '*PackSetDiskCacheTest' \
    --tests '*PackSetClientTest'
  ./gradlew --rerun-tasks :resourcepacks-client:test \
    --tests '*PackSetDiskCacheTest' \
    --tests '*PackSetClientTest'
  ./gradlew --no-build-cache clean check
  git diff --check
  ```

- [ ] **Step 7: Commit the lifecycle slice**

  ```bash
  git add resourcepacks-client
  git commit -S -m "feat(client): cache and refresh packsets"
  ```

---

### Task 5: Publish resourcepacks-client as a locked Maven artifact

**Repository:** `/home/lukas/grounds/resourcepacks`

**Files:**
- Modify: `resourcepacks-client/build.gradle.kts`
- Modify: `.github/workflows/release.yml`
- Modify: `release-tools/src/maven-create-or-compare.mjs`
- Create: `release-tools/src/client-maven-create-or-compare.mjs`
- Modify: `release-tools/package.json`
- Modify: `release-tools/test/maven-create-or-compare.test.mjs`
- Modify: `resourcepacks-contract/src/test/scripts/verify-maven-local-consumers.sh`
- Create: `resourcepacks-client/src/test/scripts/verify-maven-local-consumers.sh`
- Modify: `resourcepacks-product/src/test/kotlin/gg/grounds/resourcepacks/product/AutomationContractTest.kt`

**Interfaces:**
- Consumes: the exact-four Maven decision-only gate used by catalog/contract.
- Produces: Maven coordinate `gg.grounds:resourcepacks-client:0.3.0` with JAR, sources JAR, POM, and Gradle module metadata after the feature release.

- [ ] **Step 1: Write publication and workflow contract tests first**

  Require release ordering `catalog gate/publish -> contract gate/publish -> client gate/publish -> R2`, fixed GitHub Packages origin, exact coordinate, exact four staged files, no pathname handoff, and failure preventing R2. Add a controlled workflow mutation that removes the client gate and one that moves R2 before client publish.

- [ ] **Step 2: Run RED**

  ```bash
  ./gradlew --rerun-tasks :resourcepacks-product:test --tests '*AutomationContractTest'
  ```
  Expected: contract failure because the client publication steps are absent.

- [ ] **Step 3: Add exact-four Maven publication to the client module**

  Mirror the contract module's `mavenJava`, `ReleaseStaging`, `GitHubPackages`, and `stageExactMavenPublication`, changing only `artifactId` to `resourcepacks-client`. The staged tree must contain exactly:

  ```text
  resourcepacks-client-0.3.0.jar
  resourcepacks-client-0.3.0-sources.jar
  resourcepacks-client-0.3.0.pom
  resourcepacks-client-0.3.0.module
  ```

- [ ] **Step 4: Generalize the Maven release-tool allowlist safely**

  Keep fixed repository origin and require one of the explicit coordinates `resourcepacks-contract` or `resourcepacks-client`; do not accept an arbitrary CLI group/artifact/repository. Add the client wrapper with exact args `--staging-directory`, `--version`, `--username`, `--token`.

- [ ] **Step 5: Wire the adjacent client gate/publish into release.yml**

  Insert it immediately after Contract publication and before R2. Use a separate `client-maven-gate` decision validated as exactly `publish|skip`; publish only from the unchanged Gradle publication outputs.

- [ ] **Step 6: Add fresh Maven-local Java/Kotlin consumers**

  Compile a Java and Kotlin consumer that constructs `PackSetSource`, reads `PackSetClientState`, and references `PackSetClient`; resolve `gg.grounds` only from `mavenLocal()` and public dependencies from Maven Central. Assert selected component version equals the current `version.txt`.

- [ ] **Step 7: Run release gates**

  ```bash
  ./gradlew :resourcepacks-client:publishToMavenLocal
  bash resourcepacks-client/src/test/scripts/verify-maven-local-consumers.sh "$PWD" ./gradlew
  ./gradlew --rerun-tasks :resourcepacks-product:test --tests '*AutomationContractTest'
  cd release-tools && npm ci --ignore-scripts && npm test && npm audit --audit-level=high
  cd .. && ./gradlew --no-build-cache clean check
  git diff --check
  ```

- [ ] **Step 8: Commit publication automation**

  ```bash
  git add resourcepacks-client release-tools .github/workflows/release.yml resourcepacks-product
  git commit -S -m "ci: publish resourcepacks client"
  ```

- [ ] **Step 9: Review, merge, and release resourcepacks 0.3.0**

  Push `codex/plugin-resourcepacks-client`, open a PR against `main`, run an independent contract/security review, and wait for every required check. Merge only the reviewed head, run Release Please, merge its release PR, and verify tag `v0.3.0` publishes both `gg.grounds:resourcepacks-contract:0.3.0` and `gg.grounds:resourcepacks-client:0.3.0` before starting Task 6.

---

### Task 6: Create the standalone plugin-resourcepacks repository and typed config

**Repository:** `/home/lukas/grounds/plugin-resourcepacks` (new public GitHub repository `groundsgg/plugin-resourcepacks`)

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `velocity/build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackSettings.kt`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackEnvironment.kt`
- Create: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackSettingsTest.kt`
- Create: `README.md`
- Create: `LICENSE`

**Interfaces:**
- Consumes: `gg.grounds:common:1.0.0` as a compile-only dependency supplied at runtime by the required `plugin-config` plugin, and `gg.grounds:resourcepacks-client:0.3.0` as an implementation dependency.
- Produces:
  ```kotlin
  data class ResourcePackSourceSettings(
      val baseUrl: String = "https://cdn.grounds.gg",
      val packSet: String = "grounds-global",
      val channel: String = "stable",
  )

  data class ResourcePackSettings(
      val schemaVersion: Int = 1,
      val enabled: Boolean = true,
      val source: ResourcePackSourceSettings = ResourcePackSourceSettings(),
      val required: Boolean = true,
      val prompt: String = "Grounds benötigt seine Resourcepacks.",
  ) {
      fun toClientSource(): PackSetSource
  }

  object ResourcePackSettingsDefinition : ConfigDefinition<ResourcePackSettings>(
      namespace = "resourcepacks",
      key = "global",
      type = ResourcePackSettings::class.java,
      defaultValue = ResourcePackSettings(),
  )

  data class ResourcePackEnvironment(val deploymentEnvironment: String) {
      companion object { fun from(environment: Map<String, String>): ResourcePackEnvironment }
  }
  ```

- [ ] **Step 1: Verify the fixed coordinates and create the empty public repository**

  Run read-only coordinate inspection first:
  ```bash
  ./gradlew -p /home/lukas/grounds/plugin-config :common:publishToMavenLocal
  test -f ~/.m2/repository/gg/grounds/common/1.0.0/common-1.0.0.jar
  ./gradlew -p /home/lukas/grounds/resourcepacks :resourcepacks-client:publishToMavenLocal
  test -f ~/.m2/repository/gg/grounds/resourcepacks-client/*/resourcepacks-client-*.jar
  ```
  Then run:
  ```bash
  gh repo create groundsgg/plugin-resourcepacks --public --description "Velocity PackSet delivery plugin" --disable-wiki --add-readme --gitignore Kotlin --license apache-2.0
  gh repo clone groundsgg/plugin-resourcepacks /home/lukas/grounds/plugin-resourcepacks
  git -C /home/lukas/grounds/plugin-resourcepacks switch -c codex/plugin-resourcepacks
  ```
  Enable the same required-check branch rule as `groundsgg/plugin-config` through the GitHub repository settings after the initial CI workflow exists. Do not push implementation until its local gates pass.

- [ ] **Step 2: Scaffold the Gradle project from current Grounds conventions**

  Use base conventions 0.8.0, one `velocity` module, GitHub Packages plus Maven Central, Java 25/JVM 24, locked dependencies, and `gg.grounds.velocity-conventions`. Do not copy `plugin-player` source or add a `common` module without a concrete shared consumer.

- [ ] **Step 3: Write config tests before model code**

  Assert the exact default JSON-equivalent values, definition namespace/key/type, and environment parsing from `GROUNDS_ENVIRONMENT`. Reject blank, slash, backslash, whitespace, or missing environment values. Assert source conversion maps `stable|edge` case-sensitively to `PackSetChannel` and delegates URI safety to `PackSetSource`.

- [ ] **Step 4: Run RED and implement the config boundary**

  ```bash
  ./gradlew --rerun-tasks :velocity:test --tests '*ResourcePackSettingsTest'
  ```
  Expected RED: missing settings types. Implement the minimal data classes/definition and rerun to green.

- [ ] **Step 5: Write truthful README setup**

  Document required `plugin-config`, the exact config scope `network/<env>/resourcepacks/global`, default Stable seed, Stage Edge override, `GROUNDS_ENVIRONMENT`, zero-I/O login guarantee, cache location, and no Minestom/gamemode overlay support yet. State explicitly that an arbitrary HTTPS origin is an administrator capability and config writes must remain inside the current authenticated/private service-config boundary until application-level admin authorization exists.

- [ ] **Step 6: Lock dependencies and commit scaffold**

  ```bash
  ./gradlew dependencies --write-locks
  ./gradlew --no-build-cache clean check
  git diff --check
  git add .
  git commit -S -m "feat: scaffold resourcepacks plugin"
  ```

---

### Task 7: Implement Velocity lifecycle, dispatch, reconciliation, and status handling

**Repository:** `/home/lukas/grounds/plugin-resourcepacks`

**Files:**
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/GroundsResourcePacksPlugin.kt`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/VelocityPackRequestFactory.kt`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackCoordinator.kt`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackMetrics.kt`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackRuntimeStatus.kt`
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackStatusListener.kt`
- Create: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/VelocityPackRequestFactoryTest.kt`
- Create: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackCoordinatorTest.kt`
- Create: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/GroundsResourcePacksPluginTest.kt`
- Create: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackStatusListenerTest.kt`

**Interfaces:**
- Consumes: Task 6 settings and Task 4 `PackSetClient` state/listener API.
- Produces:
  ```kotlin
  fun interface OnlinePlayerView { fun players(): Collection<Player> }
  fun interface PackSender { fun send(player: Player, request: ResourcePackRequest) }

  class ResourcePackCoordinator(
      private val settings: () -> ResourcePackSettings?,
      private val clientState: () -> PackSetClientState,
      private val players: OnlinePlayerView,
      private val sender: PackSender,
      private val requestFactory: VelocityPackRequestFactory,
  ) {
      fun onLogin(player: Player)
      fun onSnapshot(state: PackSetClientState)
      fun onSettingsChanged(settings: ResourcePackSettings)
      fun forget(playerId: UUID)
  }

  data class ResourcePackRuntimeStatus(
      val clientStatus: PackSetClientStatus,
      val currentFingerprint: String?,
      val fallbackFingerprint: String?,
      val lastError: String?,
      val requested: Long,
      val accepted: Long,
      val downloaded: Long,
      val failed: Long,
      val declined: Long,
  )
  ```

- [ ] **Step 1: Write pure request-factory and coordinator tests**

  Assert manifest order, UUID, URI, SHA-1, `required`, configured prompt, zero send when disabled/unavailable, one send on login, identical fingerprint suppression, changed fingerprint resend to all online players once, per-player disconnect cleanup, old-source degraded fallback never sent, and client/config suppliers never performing HTTP inside `onLogin`.

- [ ] **Step 2: Run focused RED**

  ```bash
  ./gradlew --rerun-tasks :velocity:test \
    --tests '*VelocityPackRequestFactoryTest' \
    --tests '*ResourcePackCoordinatorTest'
  ```
  Expected: compile failure for missing factory/coordinator.

- [ ] **Step 3: Implement Adventure request mapping**

  Build one ordered `ResourcePackRequest` using the Adventure API shape already compiled in `library-gui`:
  ```kotlin
  val infos = snapshot.packs.sortedBy(ResolvedPack::order).map { pack ->
      ResourcePackInfo.resourcePackInfo(pack.uuid, pack.uri, pack.sha1)
  }
  return ResourcePackRequest.resourcePackRequest()
      .packs(infos)
      .required(settings.required)
      .prompt(Component.text(settings.prompt))
      .build()
  ```
- [ ] **Step 4: Implement coordinator duplicate suppression**

  Store `ConcurrentHashMap<UUID, String>` of last sent fingerprint. Insert only after `sendResourcePacks` returns normally. On settings changes, clear sent fingerprints if `required` or `prompt` changed; reconfigure/refresh client if source changed; disabling clears fingerprints but does not send or remove packs.

- [ ] **Step 5: Write plugin lifecycle tests**

  Require `@Plugin(dependencies = [Dependency(id = "plugin-config")])`; resolve with `VelocityConfigManagerServices.require(proxy)`; register `ResourcePackSettingsDefinition` with `app="network"`, `env=GROUNDS_ENVIRONMENT`, `ConfigStartupMode.DEGRADED`; use `dataDirectory.resolve("packset-cache")`; subscribe to changes; lazily create and start exactly one client when the registration is usable or the first valid config change arrives; register PostLogin, Disconnect, and status listeners; close the client on proxy shutdown. A degraded `NOT_READY` registration keeps settings/current snapshot null and sends nothing rather than silently applying defaults over an unknown operator value.

- [ ] **Step 6: Implement lifecycle and event adapters**

  Keep every refresh on the client scheduler. Velocity event handlers may only read atomic state, build a request from immutable data, and call the Velocity API. On source change, call `client.reconfigure(newSource)`; on prompt/required/enabled change, call coordinator reconciliation without reconstructing the HTTP client.

- [ ] **Step 7: Implement health/status counters and credential-safe logging**

  Count requested, accepted, downloaded, failed, declined, and invalid-url statuses with `LongAdder`. Expose an immutable `ResourcePackRuntimeStatus` snapshot from the plugin for local diagnostics and log every READY/DEGRADED/UNAVAILABLE transition with current/fallback fingerprints and sanitized reason. Log player UUID, pack UUID, configured target ID, and terminal status; never log config-service tokens, R2 credentials, response bodies, or player IPs.

- [ ] **Step 8: Run focused tests twice and full plugin checks**

  ```bash
  ./gradlew --rerun-tasks :velocity:test
  ./gradlew --rerun-tasks :velocity:test
  ./gradlew --no-build-cache clean check
  git diff --check
  ```

- [ ] **Step 9: Commit runtime behavior**

  ```bash
  git add velocity README.md
  git commit -S -m "feat: deliver cached packsets on velocity"
  ```

---

### Task 8: Add plugin CI, release automation, and repository contract checks

**Repository:** `/home/lukas/grounds/plugin-resourcepacks`

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Create: `.github/workflows/release-please.yml`
- Create: `.github/workflows/labels.yml`
- Create: `release-please-config.json`
- Create: `.release-please-manifest.json`
- Create: `.github/pull_request_template.md`
- Create: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/AutomationContractTest.kt`

**Interfaces:**
- Consumes: Grounds reusable Gradle CI/publish/release-please workflows.
- Produces: signed/tagged plugin release `0.1.0` and `ghcr.io/groundsgg/plugin-resourcepacks:0.1.0` compatible with `plugin-velocity-jar`.

- [ ] **Step 1: Write structured automation tests**

  Parse YAML with test-only locked SnakeYAML. Assert main-only CI, JDK 25, exact checkout SHA with `persist-credentials:false`, `clean check`, release-please token wiring, tag-only release, job-scoped permissions, and no resourcepack/R2/CDN secrets in this repository. Add controlled mutations for broad write permissions, missing clean test, mutable checkout, and missing tag trigger.

- [ ] **Step 2: Run RED**

  ```bash
  ./gradlew --rerun-tasks :velocity:test --tests '*AutomationContractTest'
  ```
  Expected: missing workflow files.

- [ ] **Step 3: Add workflows from current Grounds plugin templates**

  Use `groundsgg/.github/.github/workflows/gradle-ci.yml@main`, `gradle-publish.yml@main`, `release-please.yml@main`, and `label-sync.yml@main`, matching current plugin-config/plugin-permissions token contracts. Release Please starts at `0.1.0` and uses the canonical Grounds PR template sections.

- [ ] **Step 4: Run automation and actionlint gates**

  ```bash
  ./gradlew --rerun-tasks :velocity:test --tests '*AutomationContractTest'
  actionlint .github/workflows/*.yml
  ./gradlew --no-build-cache clean check
  git diff --check
  ```

- [ ] **Step 5: Commit and open the plugin PR**

  ```bash
  git add .github release-please-config.json .release-please-manifest.json velocity
  git commit -S -m "ci: automate resourcepacks plugin releases"
  git push -u origin codex/plugin-resourcepacks
  gh pr create --repo groundsgg/plugin-resourcepacks --base main --head codex/plugin-resourcepacks --title "feat: add resourcepack delivery plugin" --body-file /tmp/plugin-resourcepacks-pr.md
  ```
  Do not merge until required checks and an independent code review are green.

- [ ] **Step 6: Release plugin-resourcepacks 0.1.0**

  Merge the reviewed PR, run Release Please, merge the generated `0.1.0` release PR, and verify the tag workflow publishes `ghcr.io/groundsgg/plugin-resourcepacks:0.1.0`. Confirm the served `/plugin.jar` contains the Velocity plugin annotation and resolves required dependency `plugin-config` before changing either bundle repository.

---

### Task 9: Add plugin-config and plugin-resourcepacks to the PlatformBundle

**Repository:** `/home/lukas/grounds/library-platform-bundle`

**Files:**
- Modify: `bundle.yaml`
- Modify: `README.md`
- Modify: `docs/bundle-reference.md`
- Modify: `scripts/validate-bundle.py`

**Interfaces:**
- Consumes: released `plugin-config:1.0.0` and `plugin-resourcepacks:0.1.0`.
- Produces: `plugin-config` and `plugin-resourcepacks` component definitions, both present in `velocity` and `velocity-2` plugin lists.

- [ ] **Step 1: Write the bundle contract test first**

  Require two new `plugin-velocity` components with chart `oci://ghcr.io/groundsgg/charts/plugin-velocity-jar`, images `ghcr.io/groundsgg/plugin-config:1.0.0` and `ghcr.io/groundsgg/plugin-resourcepacks:0.1.0`, and both proxies containing each plugin exactly once. Require proxy env entries:

  ```yaml
  - name: CONFIG_SERVICE_URL
    value: http://service-config:9000
  - name: CONFIG_NATS_URL
    valueFrom: shared.nats
  - name: GROUNDS_ENVIRONMENT
    value: stage
  ```

  Assert existing `groundsToken` remains enabled for both proxies.

- [ ] **Step 2: Run bundle validation and record RED**

  Run:
  ```bash
  python3 scripts/validate-bundle.py
  ```
  Expected: assertion failure because the new component/plugin references are absent.

- [ ] **Step 3: Add component and proxy composition**

  Add `plugin-config` and `plugin-resourcepacks` beside `plugin-player`, each with 10m CPU/16Mi request and 50m CPU/32Mi limit unless image observation proves a higher baseline necessary. Put `plugin-config` before `plugin-resourcepacks` in the human-readable list; Velocity still enforces the declared dependency.

- [ ] **Step 4: Update operator docs and validate**

  Document that the projected Grounds token is consumed by plugin-config HTTP/NATS, while plugin-resourcepacks uses public CDN only. Run bundle schema tests, YAML lint, `git diff --check`, and render both proxy values if the repository exposes a render command.

- [ ] **Step 5: Commit and PR**

  ```bash
  git add bundle.yaml README.md docs
  git commit -S -m "feat(bundle): add resourcepack delivery plugins"
  ```
  Push a `codex/plugin-resourcepacks` branch and open a PR; merge only after bundle validation passes.

---

### Task 10: Deploy both plugins and Stage Edge configuration

**Repository:** `/home/lukas/grounds/deploy`

**Files:**
- Create: `environments/stage/components/plugin-config/component.yaml`
- Create: `environments/stage/components/plugin-config/values.yaml`
- Create: `environments/stage/components/plugin-resourcepacks/component.yaml`
- Create: `environments/stage/components/plugin-resourcepacks/values.yaml`
- Modify: `environments/stage/components/velocity/values.yaml`
- Modify: `environments/stage/components/velocity-2/values.yaml`
- Create: `environments/stage/config/resourcepacks-global.json`
- Create: `scripts/validate-stage-resourcepacks.py`
- Create: `scripts/apply-stage-resourcepacks-config.sh`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: released plugin container tags and the plugin-config default document.
- Produces: both Stage proxies loading `plugin-config` and `plugin-resourcepacks`; Stage document selecting Edge.

- [ ] **Step 1: Write deployment contract tests first**

  Assert component names/charts/images/tags, both proxy plugin lists, exact `CONFIG_SERVICE_URL=http://service-config:9000`, exact `CONFIG_NATS_URL=nats://nats.nats.svc.cluster.local:4222`, `GROUNDS_ENVIRONMENT=stage`, `groundsToken.enabled=true`, and the exact Stage document:

  ```json
  {
    "schemaVersion": 1,
    "enabled": true,
    "source": {
      "baseUrl": "https://cdn.grounds.gg",
      "packSet": "grounds-global",
      "channel": "edge"
    },
    "required": true,
    "prompt": "Grounds benötigt seine Resourcepacks."
  }
  ```

- [ ] **Step 2: Run deployment validation and record RED**

  Run:
  ```bash
  python3 scripts/validate-stage-resourcepacks.py
  ```
  Expected: assertion failure because component files and plugin references are absent.

- [ ] **Step 3: Add plugin component releases**

  Mirror `plugin-player`'s `plugin-velocity-jar` component shape, pin `plugin-config` to `1.0.0` and `plugin-resourcepacks` to `0.1.0`, and keep the serving pods minimal. Add both plugins to both proxy lists; add the three environment variables to both proxies without changing presence, chat, permissions, or proxy settings.

- [ ] **Step 4: Add an idempotent Stage config apply path**

  Store the desired JSON in Git. `scripts/apply-stage-resourcepacks-config.sh` must require `CONFIG_ADMIN_TARGET`, `GROUNDS_TOKEN_FILE`, `UPDATED_BY`, `grpcurl`, and `jq`; it uses the checked-in sibling contract path `/home/lukas/grounds/grpc-contracts/config/src/main/proto` when run from the Grounds operator workspace. It first calls `gg.grounds.grpc.config.ConfigAdminService/GetDocument` for `app=network`, `env=stage`, `namespace=resourcepacks`, `configKey=global`; it calls `CreateDocument` on NOT_FOUND or `PutDocument` with the returned `expectedVersion` when bytes differ. Pass `authorization: Bearer <token>` through grpcurl header input, disable shell tracing, never echo the token, and exit non-zero on optimistic-concurrency failure. If the default was already seeded as Stable, this performs the single intended Stable-to-Edge change.

- [ ] **Step 5: Render and validate Stage**

  Run:
  ```bash
  python3 scripts/validate-stage-resourcepacks.py
  python3 - <<'PY'
  import glob, yaml
  for path in glob.glob('environments/stage/components/*/*.yaml'):
      list(yaml.safe_load_all(open(path, encoding='utf-8')))
  PY
  git diff --check
  git grep -nE 'R2_(ACCESS|SECRET)|80363a6e|cfe6c29d' -- environments/stage && exit 1 || true
  ```
  Then inspect the rendered Argo applications for both plugin init downloads, config env values, and projected token path before merging.

- [ ] **Step 6: Commit and PR**

  ```bash
  git add environments/stage
  git commit -S -m "feat(stage): deploy resourcepack delivery"
  ```
  Push a `codex/plugin-resourcepacks` branch and open a PR. Let Argo reconcile only after plugin images exist.

---

### Task 11: Perform end-to-end rollout and resilience acceptance

**Repositories:** `/home/lukas/grounds/resourcepacks`, `/home/lukas/grounds/plugin-resourcepacks`, `/home/lukas/grounds/library-platform-bundle`, `/home/lukas/grounds/deploy`

**Files:**
- Modify: `/home/lukas/grounds/plugin-resourcepacks/README.md`
- Create: `/home/lukas/grounds/plugin-resourcepacks/docs/operations.md`
- Create: `/home/lukas/grounds/plugin-resourcepacks/docs/acceptance/2026-08-17-stage-rollout.md`

**Interfaces:**
- Consumes: merged/released artifacts and reconciled Stage deployment.
- Produces: repeatable operator checks and captured non-secret evidence.

- [ ] **Step 1: Verify release artifacts before rollout**

  Confirm Maven coordinates resolve, plugin-config and plugin-resourcepacks images exist at immutable tags, the active Edge pointer is canonical, and both referenced pack URLs return expected length/hash/MIME/cache headers. Record only versions, public URLs, hashes, and timestamps.

- [ ] **Step 2: Verify both proxies start cleanly**

  Check both Velocity logs for plugin-config startup, config registration at `network/stage/resourcepacks/global`, PackSet client activation, no dependency errors, and the same Edge fingerprint. Confirm login handling logs no synchronous HTTP operation.

- [ ] **Step 3: Exercise the five required runtime scenarios**

  1. Fresh login receives content then platform in manifest order and both activate.
  2. A second login on the same proxy uses the in-memory snapshot; CDN/config request counters do not increase on the login path.
  3. Advance Edge to a new valid build; each connected player receives exactly one replacement stack.
  4. Make the CDN endpoint unavailable in a controlled test window; refresh reports degraded while the matching-source last-known-good snapshot remains dispatchable.
  5. Restart one proxy during the outage; it revalidates persisted bytes, starts degraded, and serves the same matching-source snapshot without a network dependency.

- [ ] **Step 4: Exercise config transitions**

  Set `enabled=false` and confirm no new login dispatch. Restore it and confirm one dispatch. Change Stage source Edge-to-Stable and confirm the old Edge snapshot moves to `degradedFallback` and is not sent until Stable validates; change back to Edge after the check.

- [ ] **Step 5: Verify status and required-pack behavior**

  Accept, decline, fail download, and successfully load in a test client. Confirm counters/logs contain player UUID, pack UUID, target, status; confirm required decline follows Velocity's required-pack disconnect semantics and no credential/player-IP leakage occurs.

- [ ] **Step 6: Capture evidence and update operations docs**

  Write exact commands for reading/updating the global config, inspecting current/fallback fingerprint, interpreting health states, recovering from invalid documents, rotating channels, and cleaning only plugin-owned cache directories while the proxy is stopped.

- [ ] **Step 7: Run final cross-repository verification**

  ```bash
  cd /home/lukas/grounds/resourcepacks && ./gradlew --no-build-cache clean check
  cd /home/lukas/grounds/plugin-resourcepacks && ./gradlew --no-build-cache clean check
  cd /home/lukas/grounds/resourcepacks/release-tools && npm ci --ignore-scripts && npm test && npm audit --audit-level=high
  git -C /home/lukas/grounds/resourcepacks diff --check
  git -C /home/lukas/grounds/plugin-resourcepacks diff --check
  git -C /home/lukas/grounds/library-platform-bundle diff --check
  git -C /home/lukas/grounds/deploy diff --check
  ```

- [ ] **Step 8: Commit operational documentation**

  ```bash
  cd /home/lukas/grounds/plugin-resourcepacks
  git add README.md docs
  git commit -S -m "docs: add resourcepack rollout operations"
  ```

---

## Final Review Gate

- [ ] Independently review the `resourcepacks-contract` policy split for compatibility and any validation weakening.
- [ ] Independently review client HTTP/cache code for SSRF, redirect, symlink, bounded-read, concurrency, and stale-source errors.
- [ ] Independently review Velocity code for event-thread I/O, duplicate sends, ordering, required semantics, and shutdown leaks.
- [ ] Independently review GitHub Actions and deployment diffs for token scope, command injection, mutable tags, and secret exposure.
- [ ] Re-run every repository's clean gate from a fresh checkout at the exact reviewed commits.
- [ ] Merge in dependency order: `resourcepacks` release, `plugin-resourcepacks` release, PlatformBundle, then deploy.
