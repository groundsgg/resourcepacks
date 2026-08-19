package gg.grounds.resourcepacks.product

import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

class AutomationContractTest {
    private val root =
        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    private val githubSha = "${'$'}{{ github.sha }}"
    private val versionOutput = "${'$'}{{ steps.version.outputs.value }}"
    private val releaseArtifactName = "immutable-packset-release"
    private val releaseArtifactPath = "${'$'}{{ runner.temp }}/release"

    @Test
    fun `automation and repository contracts are fully bound`() {
        assertCi(parseWorkflow("ci.yml"))
        assertRelease(parseWorkflow("release.yml"))
        assertEdge(parseWorkflow("edge.yml"))
        assertReleasePlease(parseWorkflow("release-please.yml"))
        assertReleaseConfiguration(
            parseDocument(root.resolve("release-please-config.json").readText()),
            parseDocument(root.resolve(".release-please-manifest.json").readText()),
        )
        assertDocumentation(
            root.resolve("README.md").readText(),
            root.resolve(".github/pull_request_template.md").readText(),
        )
    }

    @Test
    fun `controlled mutations reject every reviewed weak binding`() {
        val ci = workflowSource("ci.yml")
        val release = workflowSource("release.yml")
        val edge = workflowSource("edge.yml")

        listOf(
                removeStep(edge, "Build raw current-commit PackSet"),
                replaceFirstOf(edge, "name: prepared-edge-packset", "name: wrong-prepared-edge", 3),
                replaceOnce(
                    edge,
                    "--base-url https://cdn.grounds.gg",
                    "--base-url https://attacker.example",
                ),
                replaceOnce(edge, "--channel edge", "--channel stable"),
                replaceOnce(edge, "--sequence '${'$'}{{ github.run_number }}'", "--sequence '1'"),
                replaceOnce(
                    edge,
                    "  edge-channel:\n    needs: [build, public-cdn]\n",
                    "  edge-channel:\n    if: always()\n    needs: [build, public-cdn]\n",
                ),
                replaceOnce(
                    edge,
                    "  edge-channel:\n    needs: [build, public-cdn]\n",
                    "  edge-channel:\n",
                ),
                replaceOnce(
                    edge,
                    "  edge-channel:\n    needs: [build, public-cdn]\n",
                    "  edge-channel:\n    needs: [build]\n",
                ),
                replaceFirstOf(edge, "environment: edge", "environment: production", 2),
                replaceFirstOf(
                    edge,
                    "      contents: read\n    steps:\n      - uses: actions/checkout@v7",
                    "      contents: write\n    steps:\n      - uses: actions/checkout@v7",
                    3,
                ),
            )
            .forEach { assertFails { assertEdge(parseText(it)) } }

        // Retain the three previously reviewed mutations.
        val sharedCheckoutPath = replaceOnce(ci, "path: run2/src", "path: run1/src")
        assertFails { assertCi(parseText(sharedCheckoutPath)) }
        val maskedDecision =
            replaceFirstOf(release, "decision=$(node", "echo \"decision=$(node", 3).let {
                replaceOnce(
                    it,
                    "          case \"\$decision\" in\n" +
                        "            publish|skip) ;;\n" +
                        "            *) echo \"Maven decision is invalid\" >&2; exit 1 ;;\n" +
                        "          esac\n",
                    "",
                )
            }
        assertFails { assertRelease(parseText(maskedDecision)) }
        val overBroadPermissions =
            replaceOnce(release, "      packages: write\n", "")
                .replace(
                    "permissions:\n  contents: read",
                    "permissions:\n  contents: write\n  packages: write",
                )
        assertFails { assertRelease(parseText(overBroadPermissions)) }
        val ciWithoutPackageRead = replaceOnce(ci, "  packages: read\n", "")
        assertFails { assertCi(parseText(ciWithoutPackageRead)) }
        val ciWithoutResolutionToken =
            replaceOnce(ci, "      GITHUB_TOKEN: \${{ github.token }}\n", "")
        assertFails { assertCi(parseText(ciWithoutResolutionToken)) }
        val restoredSecondCleanBuild =
            replaceOnce(
                ci,
                "      - name: Build clean checkout\n" +
                    "        working-directory: run1/src\n" +
                    "        run: ./gradlew --no-build-cache clean build -PpackSetVersion='\${{ steps.version.outputs.value }}'\n",
                "      - name: Build clean checkout\n" +
                    "        working-directory: run1/src\n" +
                    "        run: ./gradlew --no-build-cache clean build -PpackSetVersion='\${{ steps.version.outputs.value }}'\n" +
                    "      - name: Build clean checkout two\n" +
                    "        working-directory: run2/src\n" +
                    "        run: ./gradlew --no-build-cache clean build -PpackSetVersion='\${{ steps.version.outputs.value }}'\n",
            )
        assertFails { assertCi(parseText(restoredSecondCleanBuild)) }
        val restoredSecondEdgePackSet =
            replaceOnce(
                edge,
                "      - name: Build raw current-commit PackSet\n" +
                    "        run: ./gradlew --no-build-cache :resourcepacks-product:buildPackSet",
                "      - name: Build raw current-commit PackSet\n" +
                    "        run: ./gradlew --no-build-cache :resourcepacks-product:buildPackSet" +
                    " -PpackSetVersion='\${{ steps.edge.outputs.version }}'" +
                    " -PprovenanceCommit='\${{ steps.edge.outputs.commit }}'" +
                    " -PpublicationType=build" +
                    " -PpublicationId='\${{ steps.edge.outputs.commit }}'" +
                    " -PreleaseOutput=\"\$RUNNER_TEMP/raw-edge\"\n" +
                    "      - name: Build raw current-commit PackSet two\n" +
                    "        run: ./gradlew --no-build-cache :resourcepacks-product:buildPackSet",
            )
        assertFails { assertEdge(parseText(restoredSecondEdgePackSet)) }
        val releaseWithoutBuildPackageRead = replaceOnce(release, "      packages: read\n", "")
        assertFails { assertRelease(parseText(releaseWithoutBuildPackageRead)) }
        val missingContractPublication =
            removeStep(release, "Stage the exact Contract Maven publication")
        assertFails { assertRelease(parseText(missingContractPublication)) }
        val missingClientGate =
            replaceOnce(release, "id: client-maven-gate", "id: missing-client-maven-gate")
        assertFails { assertRelease(parseText(missingClientGate)) }
        val r2BeforeClientPublication =
            replaceOnce(
                release,
                "      - name: Publish the unchanged Client Maven staging workspace\n" +
                    "        if: steps.client-maven-gate.outputs.decision == 'publish'\n" +
                    "        run: ./gradlew :resourcepacks-client:publishMavenJavaPublicationToGitHubPackagesRepository -PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'\n" +
                    "      - name: Create or compare immutable R2 ZIP objects\n" +
                    "        run: $r2Command\n",
                "      - name: Create or compare immutable R2 ZIP objects\n" +
                    "        run: $r2Command\n" +
                    "      - name: Publish the unchanged Client Maven staging workspace\n" +
                    "        if: steps.client-maven-gate.outputs.decision == 'publish'\n" +
                    "        run: ./gradlew :resourcepacks-client:publishMavenJavaPublicationToGitHubPackagesRepository -PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'\n",
            )
        assertFails { assertRelease(parseText(r2BeforeClientPublication)) }
        val releaseWithSecretInsteadOfBuiltInToken =
            replaceExactly(
                release,
                "GITHUB_TOKEN: \${{ github.token }}",
                "GITHUB_TOKEN: \${{ secrets.PACKAGES_TOKEN }}",
                2,
            )
        assertFails { assertRelease(parseText(releaseWithSecretInsteadOfBuiltInToken)) }
        val publicCdnWithPackageToken =
            replaceOnce(
                release,
                "  public-cdn:\n    needs: publish\n",
                "  public-cdn:\n    needs: publish\n    env:\n" +
                    "      GITHUB_TOKEN: \${{ github.token }}\n",
            )
        assertFails { assertRelease(parseText(publicCdnWithPackageToken)) }

        // New full-binding mutations. Every expected value below is a literal independent of the
        // mutated document, so these cannot pass by deriving an expectation from the fixture.
        val changedArtifactBinding =
            replaceFirstOf(
                    release,
                    "          name: immutable-packset-release",
                    "          name: mutable-packset-release",
                    5,
                )
                .let {
                    replaceFirstOf(
                        it,
                        "          path: \${{ runner.temp }}/release",
                        "          path: \${{ runner.temp }}/other-release",
                        5,
                    )
                }
        assertFails { assertRelease(parseText(changedArtifactBinding)) }
        val removedLocalVerification = removeStep(release, "Locally verify the immutable release")
        assertFails { assertRelease(parseText(removedLocalVerification)) }
        val changedJava = replaceExactly(release, "java-version: '25'", "java-version: '21'", 2)
        val changedNode = replaceExactly(release, "node-version: '24'", "node-version: '22'", 5)
        assertFails { assertRelease(parseText(changedJava)) }
        assertFails { assertRelease(parseText(changedNode)) }
        val removedGitHubCommand = removeStep(release, "Attach only byte-identical release assets")
        assertFails { assertRelease(parseText(removedGitHubCommand)) }
        val changedImmutableR2Binding =
            replaceFirstOf(
                release,
                "--bucket '\${{ secrets.R2_BUCKET }}'",
                "--container '\${{ secrets.R2_OTHER_BUCKET }}'",
                2,
            )
        val changedStableR2Binding =
            replaceLastOf(
                release,
                "--bucket '\${{ secrets.R2_BUCKET }}'",
                "--container '\${{ secrets.R2_OTHER_BUCKET }}'",
                2,
            )
        assertFails { assertRelease(parseText(changedImmutableR2Binding)) }
        assertFails { assertRelease(parseText(changedStableR2Binding)) }
        val unsafeRefInterpolation =
            replaceOnce(release, "tag=\"\$RELEASE_TAG\"", "tag='${'$'}{{ github.ref_name }}'")
        assertFails { assertRelease(parseText(unsafeRefInterpolation)) }

        val config = root.resolve("release-please-config.json").readText()
        val changedReleaseConfig = replaceExactly(config, "\"version.txt\"", "\"VERSION\"", 2)
        assertFails {
            assertReleaseConfiguration(
                parseDocument(changedReleaseConfig),
                parseDocument(root.resolve(".release-please-manifest.json").readText()),
            )
        }
        assertFails {
            assertReleaseConfiguration(parseDocument(config), mapOf("." to "999.999.999"))
        }
    }

    @Test
    fun `Maven decision gate rejects failed empty and invalid output before its successor`() {
        val gate =
            workflowSource("release.yml")
                .substringAfter("id: maven-gate")
                .substringBefore("      - name: Publish")
        assertMavenDecisionGateRejects(gate)
    }

    @Test
    fun `Contract Maven decision gate rejects failed empty and invalid output before its successor`() {
        val gate =
            workflowSource("release.yml")
                .substringAfter("id: contract-maven-gate")
                .substringBefore("      - name: Publish")
        assertMavenDecisionGateRejects(gate)
    }

    @Test
    fun `Client Maven decision gate rejects failed empty and invalid output before its successor`() {
        val gate =
            workflowSource("release.yml")
                .substringAfter("id: client-maven-gate")
                .substringBefore("      - name: Publish")
        assertMavenDecisionGateRejects(gate)
    }

    @Test
    fun `Maven local consumer harnesses restrict local resolution to Grounds artifacts`() {
        listOf(
                "resourcepacks-contract/src/test/scripts/verify-maven-local-consumers.sh" to
                    "resourcepacks-contract",
                "resourcepacks-client/src/test/scripts/verify-maven-local-consumers.sh" to
                    "resourcepacks-client",
            )
            .forEach { (path, artifact) ->
                val script = root.resolve(path).readText()
                assertTrue(
                    script.contains(
                        "mavenLocal {\n" +
                            "      content {\n" +
                            "        includeGroup(\"gg.grounds\")\n" +
                            "      }\n" +
                            "    }"
                    ),
                    "$path must restrict Maven Local to gg.grounds",
                )
                assertTrue(
                    script.contains("module.name == \"$artifact\"") &&
                        script.contains("module.group == \"tools.jackson.core\""),
                    "$path must assert local Grounds and Central Jackson selections",
                )
            }
    }

    private fun assertMavenDecisionGateRejects(gate: String) {
        listOf("fail", "", "garbage").forEach { result ->
            val directory = kotlin.io.path.createTempDirectory("maven-gate-")
            val bin = directory.resolve("bin").createDirectory()
            val sentinel = directory.resolve("successor-reached")
            bin.resolve("node")
                .writeText(
                    "#!/bin/sh\n" +
                        if (result == "fail") "exit 13\n" else "printf '%s\\n' '$result'\n"
                )
            bin.resolve("node").toFile().setExecutable(true)
            val process =
                ProcessBuilder(
                        "bash",
                        "-e",
                        "-c",
                        gate.substringAfter("run: |").trimIndent() + "\ntouch '$sentinel'\n",
                    )
                    .directory(root.toFile())
                    .apply {
                        environment()["PATH"] = "$bin:${environment().getValue("PATH")}"
                        environment()["GITHUB_OUTPUT"] = directory.resolve("output").toString()
                        environment()["RUNNER_TEMP"] = directory.toString()
                    }
                    .start()
            assertTrue(process.waitFor() != 0, "gate accepted '$result'")
            assertFalse(sentinel.isRegularFile(), "successor reached for '$result'")
        }
    }

    private fun assertCi(ci: Map<String, Any?>) {
        assertEquals("CI", scalar(ci, "name"))
        val triggers = mapping(ci, "on")
        assertEquals(setOf("push", "pull_request"), triggers.keys)
        assertEquals(mapOf("branches" to listOf("main")), mapping(triggers, "push"))
        assertEquals(null, triggers["pull_request"])
        assertEquals(mapOf("contents" to "read", "packages" to "read"), mapping(ci, "permissions"))

        val jobs = mapping(ci, "jobs")
        assertEquals(setOf("verify"), jobs.keys)
        val verify = mapping(jobs, "verify")
        assertEquals("ubuntu-24.04", scalar(verify, "runs-on"))
        assertFalse(containsKeyDeep(ci, "environment"))
        assertEquals(packageResolutionEnvironment, mapping(verify, "env"))
        assertFalse(ci.toString().contains("secrets."))
        val steps = steps(verify)

        val checkouts = actionSteps(steps, "actions/checkout@v7")
        assertEquals(2, checkouts.size)
        assertEquals(
            setOf(
                mapOf(
                    "ref" to githubSha,
                    "fetch-depth" to 1,
                    "path" to "run1/src",
                    "persist-credentials" to false,
                ),
                mapOf(
                    "ref" to githubSha,
                    "fetch-depth" to 1,
                    "path" to "run2/src",
                    "persist-credentials" to false,
                ),
            ),
            checkouts.map { mapping(it, "with") }.toSet(),
        )
        assertEquals(
            listOf(mapOf("distribution" to "temurin", "java-version" to "25")),
            actionSteps(steps, "actions/setup-java@v5").map { mapping(it, "with") },
        )
        assertEquals(
            listOf(
                mapOf(
                    "node-version" to "24",
                    "cache" to "npm",
                    "cache-dependency-path" to "run1/src/release-tools/package-lock.json",
                )
            ),
            actionSteps(steps, "actions/setup-node@v5").map { mapping(it, "with") },
        )

        val version = stepByName(steps, "Read the authoritative version once")
        assertEquals("version", scalar(version, "id"))
        assertEquals("run1/src", scalar(version, "working-directory"))
        assertEquals(
            "echo \"value=\$(tr -d '\\n' < version.txt)\" >> \"\$GITHUB_OUTPUT\"",
            scalar(version, "run"),
        )
        assertEquals(
            1,
            runScripts(steps).sumOf { Regex("\\bversion\\.txt\\b").findAll(it).count() },
        )

        assertEquals(
            "./gradlew --no-build-cache clean build -PpackSetVersion='$versionOutput'",
            run(stepByName(steps, "Build clean checkout"), "run1/src"),
        )
        assertEquals(1, runScripts(steps).count { it.contains("clean build") })
        assertEquals(
            "npm ci --ignore-scripts\nnpm test\nnpm test\nnpm audit --audit-level=high",
            run(
                stepByName(steps, "Run Node release-tool tests twice and audit dependencies"),
                "run1/src/release-tools",
            ),
        )
        assertEquals(
            "./gradlew --no-build-cache :resourcepacks-product:buildPackSet " +
                "-PpackSetVersion='$versionOutput' -PprovenanceCommit='$githubSha' " +
                "-PpublicationType=release -PpublicationId='v$versionOutput' -PreleaseOutput=\"\$RUNNER_TEMP/release-one\"",
            run(stepByName(steps, "Build release candidate one"), "run1/src"),
        )
        assertEquals(
            "./gradlew --no-build-cache :resourcepacks-product:buildPackSet " +
                "-PpackSetVersion='$versionOutput' -PprovenanceCommit='$githubSha' " +
                "-PpublicationType=release -PpublicationId='v$versionOutput' -PreleaseOutput=\"\$RUNNER_TEMP/release-two\"",
            run(stepByName(steps, "Build release candidate two"), "run2/src"),
        )
        assertEquals(
            ciInspectionScript,
            scalar(
                stepByName(steps, "Inspect exact archives and compare both release directories"),
                "run",
            ),
        )
        assertNoDestructiveCommands(steps)
    }

    private fun assertRelease(release: Map<String, Any?>) {
        assertEquals("Publish immutable PackSet release", scalar(release, "name"))
        val triggers = mapping(release, "on")
        assertEquals(setOf("push"), triggers.keys)
        assertEquals(mapOf("tags" to listOf("v*")), mapping(triggers, "push"))
        assertEquals(mapOf("contents" to "read"), mapping(release, "permissions"))
        assertEquals(
            mapOf(
                "group" to "resourcepacks-release-${'$'}{{ github.ref_name }}",
                "cancel-in-progress" to false,
            ),
            mapping(release, "concurrency"),
        )

        val jobs = mapping(release, "jobs")
        assertEquals(
            setOf("build", "publish", "public-cdn", "release-assets", "stable-channel"),
            jobs.keys,
        )
        jobs.forEach { (name, raw) ->
            assertEquals("ubuntu-24.04", scalar(mapping(raw), "runs-on"), "$name runner")
        }
        assertEquals(null, mapping(jobs, "build")["needs"])
        assertEquals("build", mapping(jobs, "publish")["needs"])
        assertEquals("publish", mapping(jobs, "public-cdn")["needs"])
        assertEquals(listOf("build", "public-cdn"), mapping(jobs, "release-assets")["needs"])
        assertEquals(listOf("build", "release-assets"), mapping(jobs, "stable-channel")["needs"])
        assertEquals(
            mapOf(
                "build" to mapOf("contents" to "read", "packages" to "read"),
                "publish" to mapOf("contents" to "read", "packages" to "write"),
                "public-cdn" to mapOf("contents" to "read"),
                "release-assets" to mapOf("contents" to "write"),
                "stable-channel" to mapOf("contents" to "read"),
            ),
            jobs.mapValues { mapping(mapping(it.value), "permissions") },
        )
        assertEquals(
            mapOf(
                "version" to "${'$'}{{ steps.release.outputs.version }}",
                "commit" to "${'$'}{{ steps.release.outputs.commit }}",
                "tag" to "${'$'}{{ steps.release.outputs.tag }}",
            ),
            mapping(mapping(jobs, "build"), "outputs"),
        )
        jobs.forEach { (name, raw) ->
            val job = mapping(raw)
            assertEquals(
                if (name == "publish" || name == "stable-channel") "production" else null,
                job["environment"],
            )
            assertEquals(
                if (name == "build" || name == "publish") packageResolutionEnvironment else null,
                job["env"],
                "$name package resolution environment",
            )
        }

        val allSteps = jobs.mapValues { steps(mapping(it.value)) }
        allSteps.forEach { (job, steps) ->
            val checkout = actionSteps(steps, "actions/checkout@v7").single()
            assertEquals(
                mapOf("ref" to githubSha, "fetch-depth" to 1, "persist-credentials" to false),
                mapping(checkout, "with"),
                "$job checkout",
            )
        }
        assertEquals(
            setOf("build", "publish"),
            allSteps.filterValues { actionSteps(it, "actions/setup-java@v5").isNotEmpty() }.keys,
        )
        listOf("build", "publish").forEach { job ->
            assertEquals(
                mapOf("distribution" to "temurin", "java-version" to "25"),
                mapping(
                    actionSteps(allSteps.getValue(job), "actions/setup-java@v5").single(),
                    "with",
                ),
            )
        }
        assertEquals(
            jobs.keys,
            allSteps.filterValues { actionSteps(it, "actions/setup-node@v5").isNotEmpty() }.keys,
        )
        allSteps.forEach { (job, steps) ->
            val expectedCachePath = "release-tools/package-lock.json"
            assertEquals(
                mapOf(
                    "node-version" to "24",
                    "cache" to "npm",
                    "cache-dependency-path" to expectedCachePath,
                ),
                mapping(actionSteps(steps, "actions/setup-node@v5").single(), "with"),
                "$job Node setup",
            )
        }

        assertReleaseBuild(allSteps.getValue("build"))
        assertReleasePublish(allSteps.getValue("publish"))
        assertPublicCdn(mapping(jobs, "public-cdn"), allSteps.getValue("public-cdn"))
        assertReleaseAssets(allSteps.getValue("release-assets"))
        assertAdvanceStable(allSteps.getValue("stable-channel"))
        allSteps.values.forEach { steps ->
            assertNoDestructiveCommands(steps)
            steps.forEach stepLoop@{ step ->
                val script = step["run"] as? String ?: return@stepLoop
                assertFalse(
                    script.contains("${'$'}{{ github.ref_name }}"),
                    "attacker-controlled ref_name must enter scripts only through step env",
                )
            }
        }
    }

    private fun assertEdge(edge: Map<String, Any?>) {
        assertEquals("Publish Edge PackSet build", scalar(edge, "name"))
        assertEquals(mapOf("branches" to listOf("main")), mapping(mapping(edge, "on"), "push"))
        assertEquals(setOf("push"), mapping(edge, "on").keys)
        assertEquals(mapOf("contents" to "read"), mapping(edge, "permissions"))
        assertEquals(
            mapOf("group" to "resourcepacks-edge-main", "cancel-in-progress" to false),
            mapping(edge, "concurrency"),
        )
        val jobs = mapping(edge, "jobs")
        assertEquals(setOf("build", "publish", "public-cdn", "edge-channel"), jobs.keys)
        assertEquals(null, mapping(jobs, "build")["needs"])
        assertEquals("build", mapping(jobs, "publish")["needs"])
        assertEquals("publish", mapping(jobs, "public-cdn")["needs"])
        assertEquals(listOf("build", "public-cdn"), mapping(jobs, "edge-channel")["needs"])
        assertEquals(
            null,
            mapping(jobs, "edge-channel")["if"],
            "edge-channel must use GitHub's default success-only needs gate",
        )
        jobs.forEach { (name, raw) ->
            val job = mapping(raw)
            assertEquals("ubuntu-24.04", scalar(job, "runs-on"), name)
            assertEquals(
                if (name == "publish" || name == "edge-channel") "edge" else null,
                job["environment"],
                name,
            )
            assertEquals(
                if (name == "build") mapOf("contents" to "read", "packages" to "read")
                else mapOf("contents" to "read"),
                mapping(job, "permissions"),
                name,
            )
        }
        val steps = jobs.mapValues { steps(mapping(it.value)) }
        steps.forEach { (name, jobSteps) ->
            val checkouts = actionSteps(jobSteps, "actions/checkout@v7")
            assertEquals(
                mapOf("ref" to githubSha, "fetch-depth" to 1, "persist-credentials" to false),
                mapping(checkouts.single(), "with"),
                name,
            )
            assertEquals(
                mapOf(
                    "node-version" to "24",
                    "cache" to "npm",
                    "cache-dependency-path" to "release-tools/package-lock.json",
                ),
                mapping(actionSteps(jobSteps, "actions/setup-node@v5").single(), "with"),
                name,
            )
            assertNoDestructiveCommands(jobSteps)
        }
        assertEquals(
            mapOf("distribution" to "temurin", "java-version" to "25"),
            mapping(actionSteps(steps.getValue("build"), "actions/setup-java@v5").single(), "with"),
        )
        val identity = stepByName(steps.getValue("build"), "Derive exact Edge identity")
        assertEquals("edge", scalar(identity, "id"))
        assertEquals(
            "short_sha=\"\${GITHUB_SHA:0:12}\"\nedge_version=\"0.0.0-edge.\${GITHUB_RUN_NUMBER}.g\${short_sha}\"\necho \"version=\$edge_version\" >> \"\$GITHUB_OUTPUT\"\necho \"commit=\$GITHUB_SHA\" >> \"\$GITHUB_OUTPUT\"",
            scalar(identity, "run"),
        )
        assertEquals(
            "npm ci --ignore-scripts\nnpm test\nnpm test\nnpm audit --audit-level=high",
            run(
                stepByName(
                    steps.getValue("build"),
                    "Run Node release-tool tests twice and audit dependencies",
                ),
                "release-tools",
            ),
        )
        assertEquals(
            "./gradlew --no-build-cache clean build " +
                "-PpackSetVersion='${'$'}{{ steps.edge.outputs.version }}' " +
                "-PprovenanceCommit='${'$'}{{ steps.edge.outputs.commit }}' " +
                "-PpublicationType=build " +
                "-PpublicationId='${'$'}{{ steps.edge.outputs.commit }}'",
            scalar(stepByName(steps.getValue("build"), "Build clean deterministic checkout"), "run"),
        )
        assertEquals(
            "./gradlew --no-build-cache :resourcepacks-product:buildPackSet -PpackSetVersion='${'$'}{{ steps.edge.outputs.version }}' -PprovenanceCommit='${'$'}{{ steps.edge.outputs.commit }}' -PpublicationType=build -PpublicationId='${'$'}{{ steps.edge.outputs.commit }}' -PreleaseOutput=\"\$RUNNER_TEMP/raw-edge\"",
            scalar(stepByName(steps.getValue("build"), "Build raw current-commit PackSet"), "run"),
        )
        assertEquals(1, runScripts(steps.getValue("build")).count { it.contains("clean build") })
        assertEquals(1, runScripts(steps.getValue("build")).count { it.contains("buildPackSet") })
        assertEquals(
            mapOf(
                "name" to "raw-edge-packset",
                "path" to "${'$'}{{ runner.temp }}/raw-edge",
                "if-no-files-found" to "error",
                "retention-days" to 1,
            ),
            mapping(stepByName(steps.getValue("build"), "Upload raw Edge PackSet"), "with"),
        )
        assertEquals(
            "node release-tools/src/prepare-edge.mjs --manifest \"\$RUNNER_TEMP/raw-edge/manifest.json\" --release-directory \"\$RUNNER_TEMP/raw-edge\" --output-directory \"\$RUNNER_TEMP/prepared-edge\" --bucket '${'$'}{{ secrets.R2_BUCKET }}' --endpoint '${'$'}{{ secrets.R2_ENDPOINT }}' --access-key '${'$'}{{ secrets.R2_ACCESS_KEY_ID }}' --secret-key '${'$'}{{ secrets.R2_SECRET_ACCESS_KEY }}'",
            scalar(
                stepByName(
                    steps.getValue("publish"),
                    "Prepare Edge PackSet from validated prior channel",
                ),
                "run",
            ),
        )
        assertEquals(
            "node release-tools/src/r2-create-or-compare.mjs --manifest \"\$RUNNER_TEMP/prepared-edge/manifest.json\" --release-directory \"\$RUNNER_TEMP/prepared-edge\" --bucket '${'$'}{{ secrets.R2_BUCKET }}' --endpoint '${'$'}{{ secrets.R2_ENDPOINT }}' --access-key '${'$'}{{ secrets.R2_ACCESS_KEY_ID }}' --secret-key '${'$'}{{ secrets.R2_SECRET_ACCESS_KEY }}'",
            scalar(
                stepByName(steps.getValue("publish"), "Create or compare immutable Edge objects"),
                "run",
            ),
        )
        assertEquals(
            mapOf(
                "name" to "prepared-edge-packset",
                "path" to "${'$'}{{ runner.temp }}/prepared-edge",
                "if-no-files-found" to "error",
                "retention-days" to 1,
            ),
            mapping(stepByName(steps.getValue("publish"), "Upload prepared Edge PackSet"), "with"),
        )
        assertEquals(
            mapOf(
                "name" to "prepared-edge-packset",
                "path" to "${'$'}{{ runner.temp }}/prepared-edge",
            ),
            mapping(
                actionSteps(steps.getValue("public-cdn"), "actions/download-artifact@v5").single(),
                "with",
            ),
        )
        assertEquals(
            "node release-tools/src/verify-cdn.mjs --manifest \"\$RUNNER_TEMP/prepared-edge/manifest.json\" --release-directory \"\$RUNNER_TEMP/prepared-edge\" --base-url https://cdn.grounds.gg",
            scalar(stepByName(steps.getValue("public-cdn"), "Verify Edge CDN bytes"), "run"),
        )
        val advance = stepByName(steps.getValue("edge-channel"), "Advance Edge channel last")
        assertEquals(
            "node release-tools/src/r2-channel-advance.mjs --channel edge --manifest \"\$RUNNER_TEMP/prepared-edge/manifest.json\" --release-directory \"\$RUNNER_TEMP/prepared-edge\" --bucket '${'$'}{{ secrets.R2_BUCKET }}' --endpoint '${'$'}{{ secrets.R2_ENDPOINT }}' --access-key '${'$'}{{ secrets.R2_ACCESS_KEY_ID }}' --secret-key '${'$'}{{ secrets.R2_SECRET_ACCESS_KEY }}' --sequence '${'$'}{{ github.run_number }}'",
            scalar(advance, "run"),
        )
        assertEquals(
            mapOf(
                "name" to "prepared-edge-packset",
                "path" to "${'$'}{{ runner.temp }}/prepared-edge",
            ),
            mapping(
                actionSteps(steps.getValue("edge-channel"), "actions/download-artifact@v5")
                    .single(),
                "with",
            ),
        )
        listOf("maven", "release-assets", "stable", "github release").forEach { forbidden ->
            assertFalse(edge.toString().lowercase().contains(forbidden), forbidden)
        }
    }

    private fun assertReleaseBuild(steps: List<Map<String, Any?>>) {
        val derive = stepByName(steps, "Derive exact tag, commit, and version")
        assertEquals("release", scalar(derive, "id"))
        assertEquals(
            mapOf("RELEASE_TAG" to "${'$'}{{ github.ref_name }}", "RELEASE_COMMIT" to githubSha),
            mapping(derive, "env"),
        )
        assertEquals(releaseDerivationScript, scalar(derive, "run"))
        assertEquals(
            "./gradlew --no-build-cache :resourcepacks-product:buildPackSet " +
                "-PpackSetVersion='${'$'}{{ steps.release.outputs.version }}' " +
                "-PprovenanceCommit='${'$'}{{ steps.release.outputs.commit }}' " +
                "-PpublicationType=release -PpublicationId='${'$'}{{ steps.release.outputs.tag }}' " +
                "-PreleaseOutput=\"\$RUNNER_TEMP/release\"",
            scalar(stepByName(steps, "Build the exact four artifacts"), "run"),
        )
        assertEquals(
            releaseLocalVerificationScript,
            scalar(stepByName(steps, "Locally verify the immutable release"), "run"),
        )
        assertEquals(
            "npm ci --ignore-scripts\nnpm test\nnpm test\nnpm audit --audit-level=high",
            run(stepByName(steps, "Verify locked release tools"), "release-tools"),
        )
        val uploadIndex = steps.indexOf(stepByName(steps, "Upload the locally verified release"))
        assertTrue(
            uploadIndex > steps.indexOf(stepByName(steps, "Locally verify the immutable release"))
        )
        assertTrue(uploadIndex > steps.indexOf(stepByName(steps, "Verify locked release tools")))
        assertEquals(
            mapOf(
                "name" to releaseArtifactName,
                "path" to releaseArtifactPath,
                "if-no-files-found" to "error",
                "retention-days" to 1,
            ),
            mapping(steps[uploadIndex], "with"),
        )
        assertEquals("actions/upload-artifact@v4", scalar(steps[uploadIndex], "uses"))
    }

    private fun assertReleasePublish(steps: List<Map<String, Any?>>) {
        assertDownloadBindings(steps)
        assertEquals(
            "npm ci --ignore-scripts",
            run(stepByName(steps, "Install locked release tools"), "release-tools"),
        )
        val stage = steps.indexOf(stepByName(steps, "Stage the exact Maven publication"))
        val gate = steps.indexOfFirst { scalar(it, "id") == "maven-gate" }
        val publish =
            steps.indexOf(stepByName(steps, "Publish the unchanged Maven staging workspace"))
        val contractStage =
            steps.indexOf(stepByName(steps, "Stage the exact Contract Maven publication"))
        val contractGate = steps.indexOfFirst { scalar(it, "id") == "contract-maven-gate" }
        val contractPublish =
            steps.indexOf(
                stepByName(steps, "Publish the unchanged Contract Maven staging workspace")
            )
        val clientStage =
            steps.indexOf(stepByName(steps, "Stage the exact Client Maven publication"))
        val clientGate = steps.indexOfFirst { scalar(it, "id") == "client-maven-gate" }
        val clientPublish =
            steps.indexOf(stepByName(steps, "Publish the unchanged Client Maven staging workspace"))
        val r2 = steps.indexOf(stepByName(steps, "Create or compare immutable R2 ZIP objects"))
        assertEquals(gate - 1, stage)
        assertEquals(gate + 1, publish)
        assertEquals(publish + 1, contractStage)
        assertEquals(contractStage + 1, contractGate)
        assertEquals(contractGate + 1, contractPublish)
        assertEquals(contractPublish + 1, clientStage)
        assertEquals(clientStage + 1, clientGate)
        assertEquals(clientGate + 1, clientPublish)
        assertEquals(clientPublish + 1, r2)
        assertEquals(
            "./gradlew :resourcepacks-catalog:stageExactMavenPublication " +
                "-PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'",
            scalar(steps[stage], "run"),
        )
        assertEquals(mavenDecisionScript, scalar(steps[gate], "run"))
        assertEquals("steps.maven-gate.outputs.decision == 'publish'", scalar(steps[publish], "if"))
        assertEquals(
            "./gradlew :resourcepacks-catalog:publishMavenJavaPublicationToGitHubPackagesRepository " +
                "-PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'",
            scalar(steps[publish], "run"),
        )
        assertEquals(
            "./gradlew :resourcepacks-contract:stageExactMavenPublication " +
                "-PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'",
            scalar(steps[contractStage], "run"),
        )
        assertEquals(contractMavenDecisionScript, scalar(steps[contractGate], "run"))
        assertEquals(
            "steps.contract-maven-gate.outputs.decision == 'publish'",
            scalar(steps[contractPublish], "if"),
        )
        assertEquals(
            "./gradlew :resourcepacks-contract:publishMavenJavaPublicationToGitHubPackagesRepository " +
                "-PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'",
            scalar(steps[contractPublish], "run"),
        )
        assertEquals(
            "./gradlew :resourcepacks-client:stageExactMavenPublication " +
                "-PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'",
            scalar(steps[clientStage], "run"),
        )
        assertEquals(clientMavenDecisionScript, scalar(steps[clientGate], "run"))
        assertEquals(
            "steps.client-maven-gate.outputs.decision == 'publish'",
            scalar(steps[clientPublish], "if"),
        )
        assertEquals(
            "./gradlew :resourcepacks-client:publishMavenJavaPublicationToGitHubPackagesRepository " +
                "-PpackSetVersion='${'$'}{{ needs.build.outputs.version }}'",
            scalar(steps[clientPublish], "run"),
        )
        assertEquals(r2Command, scalar(steps[r2], "run"))
    }

    private fun assertPublicCdn(job: Map<String, Any?>, steps: List<Map<String, Any?>>) {
        assertDownloadBindings(steps)
        val rendered = job.toString()
        assertFalse(containsKeyDeep(job, "environment"))
        assertFalse(containsKeyDeep(job, "env"))
        listOf("secrets.", "env", "R2", "r2", "CLOUDFLARE", "Cloudflare", "cloudflare").forEach {
            forbidden ->
            assertFalse(rendered.contains(forbidden), "public CDN job contains $forbidden")
        }
        assertEquals(
            "node release-tools/src/verify-cdn.mjs " +
                "--manifest \"\$RUNNER_TEMP/release/manifest.json\" " +
                "--release-directory \"\$RUNNER_TEMP/release\" " +
                "--base-url https://cdn.grounds.gg",
            scalar(
                stepByName(steps, "Verify public CDN bytes without production credentials"),
                "run",
            ),
        )
    }

    private fun assertReleaseAssets(steps: List<Map<String, Any?>>) {
        assertDownloadBindings(steps)
        val attach = stepByName(steps, "Attach only byte-identical release assets")
        assertEquals(
            mapOf(
                "EXPECTED_TAG" to "${'$'}{{ needs.build.outputs.tag }}",
                "TRIGGER_TAG" to "${'$'}{{ github.ref_name }}",
            ),
            mapping(attach, "env"),
        )
        assertEquals(
            "test \"\$EXPECTED_TAG\" = \"\$TRIGGER_TAG\"\n" +
                "node release-tools/src/release-assets-create-or-compare.mjs " +
                "--manifest \"\$RUNNER_TEMP/release/manifest.json\" " +
                "--release-directory \"\$RUNNER_TEMP/release\" " +
                "--token '${'$'}{{ secrets.GITHUB_TOKEN }}'",
            scalar(attach, "run"),
        )
    }

    private fun assertAdvanceStable(steps: List<Map<String, Any?>>) {
        assertDownloadBindings(steps)
        assertEquals(
            "npm ci --ignore-scripts",
            run(stepByName(steps, "Install locked release tools"), "release-tools"),
        )
        assertEquals(
            "node release-tools/src/r2-channel-advance.mjs " +
                "--channel stable " +
                "--manifest \"\$RUNNER_TEMP/release/manifest.json\" " +
                "--release-directory \"\$RUNNER_TEMP/release\" " +
                "--bucket '${'$'}{{ secrets.R2_BUCKET }}' " +
                "--endpoint '${'$'}{{ secrets.R2_ENDPOINT }}' " +
                "--access-key '${'$'}{{ secrets.R2_ACCESS_KEY_ID }}' " +
                "--secret-key '${'$'}{{ secrets.R2_SECRET_ACCESS_KEY }}' " +
                "--sequence '${'$'}{{ github.run_number }}'",
            scalar(stepByName(steps, "Advance Stable channel last"), "run"),
        )
    }

    private fun assertDownloadBindings(steps: List<Map<String, Any?>>) {
        val download = actionSteps(steps, "actions/download-artifact@v5").single()
        assertEquals(
            mapOf("name" to releaseArtifactName, "path" to releaseArtifactPath),
            mapping(download, "with"),
        )
    }

    private fun assertReleasePlease(please: Map<String, Any?>) {
        assertEquals("Release Please", scalar(please, "name"))
        val triggers = mapping(please, "on")
        assertEquals(setOf("push", "workflow_dispatch"), triggers.keys)
        assertEquals(mapOf("branches" to listOf("main")), mapping(triggers, "push"))
        assertEquals(null, triggers["workflow_dispatch"])
        val permissions =
            mapOf("contents" to "write", "issues" to "write", "pull-requests" to "write")
        assertEquals(permissions, mapping(please, "permissions"))
        val jobs = mapping(please, "jobs")
        assertEquals(setOf("release-please"), jobs.keys)
        val caller = mapping(jobs, "release-please")
        assertEquals(
            "groundsgg/.github/.github/workflows/release-please.yml@main",
            scalar(caller, "uses"),
        )
        assertEquals("inherit", scalar(caller, "secrets"))
        assertEquals(permissions, mapping(caller, "permissions"))
    }

    private fun assertReleaseConfiguration(config: Map<String, Any?>, manifest: Map<String, Any?>) {
        assertEquals(
            mapOf(
                "release-type" to "simple",
                "version-file" to "version.txt",
                "initial-version" to "0.1.0",
                "packages" to
                    mapOf(
                        "." to
                            mapOf(
                                "release-type" to "simple",
                                "version-file" to "version.txt",
                                "initial-version" to "0.1.0",
                                "include-component-in-tag" to false,
                            )
                    ),
            ),
            config,
        )
        assertEquals(
            mapOf("." to root.resolve("version.txt").readText().removeSuffix("\n")),
            manifest,
        )
    }

    private fun assertDocumentation(readme: String, pullRequestTemplate: String) {
        listOf(
                "## Local build",
                "-PpackSetVersion=\"\$(tr -d '\\n' < version.txt)\"",
                "-PprovenanceCommit=<40-lowercase-git-sha>",
                "-PpublicationType=release",
                "-PpublicationId=\"v\$(tr -d '\\n' < version.txt)\"",
                "grounds-*-pack-v<version>.zip",
                "grounds-resourcepack-catalog-v<version>.jar",
                "manifest.json",
                "gg.grounds:resourcepacks-catalog:<packSetVersion>",
                "GroundsGuiIds",
                "rerunning that same tag",
                "R2_BUCKET",
                "R2_ENDPOINT",
                "R2_ACCESS_KEY_ID",
                "R2_SECRET_ACCESS_KEY",
                "resourcepacks/packsets/grounds-global/releases/v<version>/",
                "channels/stable.json",
                "channels/edge.json",
                "The protected `edge` environment",
                "## Out of scope",
                "does not activate a PackSet in Config Service",
            )
            .forEach { required ->
                assertTrue(readme.contains(required), "README missing $required")
            }
        assertFalse(
            Regex("(?im)^\\s*(?:grounds|kubectl|curl|gh)\\b.*(?:config|active|activate)")
                .containsMatchIn(readme),
            "README must not provide a Config activation command",
        )

        assertEquals(
            listOf(
                "# Pull Request",
                "## Description",
                "## Type of Change",
                "## Related Issues",
                "## Testing",
                "## Checklist",
            ),
            pullRequestTemplate.lineSequence().filter { it.startsWith("#") }.toList(),
        )
        listOf(
                "- [ ] 🐛 Bug fix",
                "- [ ] ✨ New feature",
                "- [ ] 💥 Breaking change",
                "- [ ] Unit tests pass",
                "- [ ] I have performed a self-review of my own code",
            )
            .forEach { required -> assertTrue(pullRequestTemplate.contains(required)) }
    }

    private fun assertNoDestructiveCommands(steps: List<Map<String, Any?>>) {
        val forbidden =
            Regex(
                "(?i)(?:^|[\\s;&|])(?:rm|rmdir|unlink|shred|purge)\\b|--(?:clobber|overwrite)\\b|delete-object"
            )
        runScripts(steps).forEach { script ->
            assertFalse(forbidden.containsMatchIn(script), script)
        }
    }

    private fun parseWorkflow(name: String) = parseText(workflowSource(name))

    private fun workflowSource(name: String) =
        root.resolve(".github/workflows").resolve(name).readText()

    private fun parseText(text: String): Map<String, Any?> {
        val raw = Yaml().load<Any?>(text) as Map<*, *>
        return raw.entries.associate {
            (if (it.key == true) "on" else it.key.toString()) to normalize(it.value)
        }
    }

    private fun parseDocument(text: String): Map<String, Any?> =
        mapping(normalize(Yaml().load<Any?>(text)))

    private fun normalize(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to normalize(it.value) }
            is List<*> -> value.map(::normalize)
            else -> value
        }

    private fun containsKeyDeep(value: Any?, key: String): Boolean =
        when (value) {
            is Map<*, *> -> value.containsKey(key) || value.values.any { containsKeyDeep(it, key) }
            is List<*> -> value.any { containsKeyDeep(it, key) }
            else -> false
        }

    @Suppress("UNCHECKED_CAST")
    private fun mapping(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("expected mapping: $value")

    private fun mapping(parent: Map<String, Any?>, key: String) =
        mapping(parent[key] ?: error("missing $key"))

    @Suppress("UNCHECKED_CAST")
    private fun sequence(parent: Map<String, Any?>, key: String): List<Any?> =
        parent[key] as? List<Any?> ?: error("missing list $key")

    private fun steps(job: Map<String, Any?>) = sequence(job, "steps").map(::mapping)

    private fun actionSteps(steps: List<Map<String, Any?>>, action: String) =
        steps.filter { scalar(it, "uses") == action }

    private fun stepByName(steps: List<Map<String, Any?>>, name: String) =
        steps.single { scalar(it, "name") == name }

    private fun scalar(parent: Map<String, Any?>, key: String): String =
        parent[key]?.toString()?.removeSuffix("\n") ?: ""

    private fun run(step: Map<String, Any?>, workingDirectory: String): String {
        assertEquals(workingDirectory, scalar(step, "working-directory"))
        return scalar(step, "run")
    }

    private fun runScripts(steps: List<Map<String, Any?>>) =
        steps.map { scalar(it, "run") }.filter(String::isNotEmpty)

    private fun replaceOnce(source: String, old: String, replacement: String): String {
        return replaceExactly(source, old, replacement, 1)
    }

    private fun replaceExactly(
        source: String,
        old: String,
        replacement: String,
        count: Int,
    ): String {
        assertEquals(
            count,
            Regex(Regex.escape(old)).findAll(source).count(),
            "mutation anchor count: $old",
        )
        return source.replace(old, replacement)
    }

    private fun replaceFirstOf(
        source: String,
        old: String,
        replacement: String,
        expectedCount: Int,
    ): String {
        assertEquals(
            expectedCount,
            Regex(Regex.escape(old)).findAll(source).count(),
            "mutation anchor count: $old",
        )
        return source.replaceFirst(old, replacement)
    }

    private fun replaceLastOf(
        source: String,
        old: String,
        replacement: String,
        expectedCount: Int,
    ): String {
        assertEquals(expectedCount, Regex(Regex.escape(old)).findAll(source).count())
        val index = source.lastIndexOf(old)
        assertTrue(index >= 0, "missing mutation anchor $old")
        return source.replaceRange(index, index + old.length, replacement)
    }

    private fun removeStep(source: String, name: String): String {
        val marker = "      - name: $name\n"
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "missing mutation step $name")
        val end =
            source.indexOf("\n      - ", start + marker.length).let {
                if (it < 0) source.length else it + 1
            }
        return source.removeRange(start, end)
    }

    private val ciInspectionScript =
        listOf(
                "release_one=\"\$RUNNER_TEMP/release-one\"",
                "release_two=\"\$RUNNER_TEMP/release-two\"",
                "test \"\$(find \"\$release_one\" -maxdepth 1 -type f | wc -l)\" -eq 4",
                "node --input-type=module --eval 'import { readdir, readFile } from \"node:fs/promises\"; const manifest = JSON.parse(await readFile(process.argv[1], \"utf8\")); const expected = [manifest.catalog.file, ...manifest.packs.map(pack => new URL(pack.url).pathname.split(\"/\").at(-1)), \"manifest.json\"].sort(); const actual = (await readdir(process.argv[2])).sort(); if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(\"release artifact set mismatch\");' \"\$release_one/manifest.json\" \"\$release_one\"",
                "zipinfo -1 \"\$release_one\"/grounds-content-pack-*.zip",
                "zipinfo -1 \"\$release_one\"/grounds-platform-pack-*.zip",
                "zipinfo -1 \"\$release_one\"/grounds-resourcepack-catalog-*.jar",
                "cmp \"\$release_one/manifest.json\" \"\$release_two/manifest.json\"",
                "diff --no-dereference -r \"\$release_one\" \"\$release_two\"",
            )
            .joinToString("\n")

    private val releaseDerivationScript =
        listOf(
                "tag=\"\$RELEASE_TAG\"",
                "version=\"\${tag#v}\"",
                "test -n \"\$version\"",
                "test \"\$version\" = \"\$(tr -d '\\n' < version.txt)\"",
                "echo \"tag=\$tag\" >> \"\$GITHUB_OUTPUT\"",
                "echo \"version=\$version\" >> \"\$GITHUB_OUTPUT\"",
                "echo \"commit=\$RELEASE_COMMIT\" >> \"\$GITHUB_OUTPUT\"",
            )
            .joinToString("\n")

    private val releaseLocalVerificationScript =
        listOf(
                "release=\"\$RUNNER_TEMP/release\"",
                "test \"\$(find \"\$release\" -maxdepth 1 -type f | wc -l)\" -eq 4",
                "node --input-type=module --eval 'import { readdir, readFile } from \"node:fs/promises\"; const manifest = JSON.parse(await readFile(process.argv[1], \"utf8\")); const expected = [manifest.catalog.file, ...manifest.packs.map(pack => new URL(pack.url).pathname.split(\"/\").at(-1)), \"manifest.json\"].sort(); const actual = (await readdir(process.argv[2])).sort(); if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(\"release artifact set mismatch\"); if (manifest.version !== process.argv[3] || manifest.publication.type !== \"release\" || manifest.publication.id !== process.argv[4] || manifest.provenance.commit !== process.argv[5]) throw new Error(\"release manifest provenance mismatch\");' \"\$release/manifest.json\" \"\$release\" '${'$'}{{ steps.release.outputs.version }}' '${'$'}{{ steps.release.outputs.tag }}' '${'$'}{{ steps.release.outputs.commit }}'",
                "zipinfo -1 \"\$release\"/grounds-content-pack-*.zip",
                "zipinfo -1 \"\$release\"/grounds-platform-pack-*.zip",
                "zipinfo -1 \"\$release\"/grounds-resourcepack-catalog-*.jar",
            )
            .joinToString("\n")

    private val mavenDecisionScript =
        listOf(
                "decision=\$(node release-tools/src/maven-create-or-compare.mjs --manifest \"\$RUNNER_TEMP/release/manifest.json\" --staging-directory resourcepacks-catalog/build/release-maven-staging --username '${'$'}{{ github.actor }}' --token '${'$'}{{ secrets.GITHUB_TOKEN }}')",
                "case \"\$decision\" in",
                "  publish|skip) ;;",
                "  *) echo \"Maven decision is invalid\" >&2; exit 1 ;;",
                "esac",
                "echo \"decision=\$decision\" >> \"\$GITHUB_OUTPUT\"",
            )
            .joinToString("\n")

    private val contractMavenDecisionScript =
        listOf(
                "decision=\$(node release-tools/src/contract-maven-create-or-compare.mjs --staging-directory resourcepacks-contract/build/release-maven-staging --version '${'$'}{{ needs.build.outputs.version }}' --username '${'$'}{{ github.actor }}' --token '${'$'}{{ secrets.GITHUB_TOKEN }}')",
                "case \"\$decision\" in",
                "  publish|skip) ;;",
                "  *) echo \"Contract Maven decision is invalid\" >&2; exit 1 ;;",
                "esac",
                "echo \"decision=\$decision\" >> \"\$GITHUB_OUTPUT\"",
            )
            .joinToString("\n")

    private val clientMavenDecisionScript =
        listOf(
                "decision=\$(node release-tools/src/client-maven-create-or-compare.mjs --staging-directory resourcepacks-client/build/release-maven-staging --version '${'$'}{{ needs.build.outputs.version }}' --username '${'$'}{{ github.actor }}' --token '${'$'}{{ secrets.GITHUB_TOKEN }}')",
                "case \"\$decision\" in",
                "  publish|skip) ;;",
                "  *) echo \"Client Maven decision is invalid\" >&2; exit 1 ;;",
                "esac",
                "echo \"decision=\$decision\" >> \"\$GITHUB_OUTPUT\"",
            )
            .joinToString("\n")

    private val r2Command =
        "node release-tools/src/r2-create-or-compare.mjs " +
            "--manifest \"\$RUNNER_TEMP/release/manifest.json\" " +
            "--release-directory \"\$RUNNER_TEMP/release\" " +
            "--bucket '${'$'}{{ secrets.R2_BUCKET }}' " +
            "--endpoint '${'$'}{{ secrets.R2_ENDPOINT }}' " +
            "--access-key '${'$'}{{ secrets.R2_ACCESS_KEY_ID }}' " +
            "--secret-key '${'$'}{{ secrets.R2_SECRET_ACCESS_KEY }}'"

    private val packageResolutionEnvironment =
        mapOf(
            "GITHUB_ACTOR" to "${'$'}{{ github.actor }}",
            "GITHUB_TOKEN" to "${'$'}{{ github.token }}",
        )
}
