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

    @Test
    fun `workflow contracts are structurally exact`() {
        val ci = parse("ci.yml")
        val release = parse("release.yml")
        assertCi(ci)
        assertRelease(release)
        val please = parse("release-please.yml")
        assertEquals(setOf("push", "workflow_dispatch"), mapping(please, "on").keys)
        assertEquals(listOf("main"), sequence(mapping(mapping(please, "on"), "push"), "branches"))
        assertEquals(
            mapOf("contents" to "write", "issues" to "write", "pull-requests" to "write"),
            mapping(please, "permissions"),
        )
        val caller = mapping(mapping(please, "jobs"), "release-please")
        assertEquals(
            "groundsgg/.github/.github/workflows/release-please.yml@main",
            scalar(caller, "uses"),
        )
        assertEquals("inherit", scalar(caller, "secrets"))
    }

    @Test
    fun `contract mutations reject the reviewed weak workflow shapes`() {
        val ci = source("ci.yml")
        val release = source("release.yml")
        assertFails { assertCi(parseText(ci.replace("path: run2/src", "path: run1/src"))) }
        assertFails {
            assertRelease(
                parseText(
                    release
                        .replace("decision=$(node", "echo \"decision=$(node")
                        .replace(
                            "          case \"\$decision\" in\n            publish|skip) ;;\n            *) echo \"Maven decision is invalid\" >&2; exit 1 ;;\n          esac\n",
                            "",
                        )
                )
            )
        }
        assertFails {
            assertRelease(
                parseText(
                    release
                        .replace("      packages: write\n", "")
                        .replace(
                            "permissions:\n  contents: read",
                            "permissions:\n  contents: write\n  packages: write",
                        )
                )
            )
        }
    }

    @Test
    fun `Maven decision gate rejects failed empty and invalid output before the R2 successor`() {
        val gate =
            source("release.yml")
                .substringAfter("id: maven-gate")
                .substringBefore("      - name: Publish")
        listOf("fail", "", "garbage").forEach { result ->
            val directory = kotlin.io.path.createTempDirectory("maven-gate-")
            val bin = directory.resolve("bin").createDirectory()
            val sentinel = directory.resolve("r2-reached")
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
            assertTrue(process.waitFor() != 0)
            assertFalse(sentinel.isRegularFile())
        }
    }

    private fun assertCi(ci: Map<String, Any?>) {
        assertEquals(setOf("push", "pull_request"), mapping(ci, "on").keys)
        assertEquals(listOf("main"), sequence(mapping(mapping(ci, "on"), "push"), "branches"))
        assertEquals(mapOf("contents" to "read"), mapping(ci, "permissions"))
        val steps = sequence(mapping(mapping(ci, "jobs"), "verify"), "steps").map(::mapping)
        val checkouts = steps.filter { scalar(it, "uses") == "actions/checkout@v7" }
        assertEquals(2, checkouts.size)
        assertEquals(
            setOf("run1/src", "run2/src"),
            checkouts.map { scalar(mapping(it, "with"), "path") }.toSet(),
        )
        checkouts.forEach {
            assertEquals("${'$'}{{ github.sha }}", scalar(mapping(it, "with"), "ref"))
            assertEquals(false, mapping(it, "with")["persist-credentials"])
        }
        val clean = steps.filter { scalar(it, "name").startsWith("Build clean checkout") }
        val packs = steps.filter { scalar(it, "name").startsWith("Build release candidate") }
        assertEquals(
            setOf("run1/src", "run2/src"),
            clean.map { scalar(it, "working-directory") }.toSet(),
        )
        assertEquals(
            setOf("run1/src", "run2/src"),
            packs.map { scalar(it, "working-directory") }.toSet(),
        )
        clean.forEach { assertTrue(scalar(it, "run").contains("--no-build-cache clean build")) }
        packs.forEach {
            assertTrue(
                scalar(it, "run").contains("buildPackSet") &&
                    scalar(it, "run").contains("-PreleaseOutput=")
            )
        }
        assertTrue(
            scalar(steps.single { scalar(it, "name").startsWith("Inspect") }, "run")
                .contains("diff -r")
        )
    }

    private fun assertRelease(release: Map<String, Any?>) {
        assertEquals(listOf("v*"), sequence(mapping(mapping(release, "on"), "push"), "tags"))
        assertEquals(mapOf("contents" to "read"), mapping(release, "permissions"))
        val concurrency = mapping(release, "concurrency")
        assertEquals(false, concurrency["cancel-in-progress"])
        assertTrue(scalar(concurrency, "group").contains("github.ref_name"))
        val jobs = mapping(release, "jobs")
        assertEquals(setOf("build", "publish", "public-cdn", "release-assets"), jobs.keys)
        assertEquals("build", scalar(mapping(jobs, "publish"), "needs"))
        assertEquals("publish", scalar(mapping(jobs, "public-cdn"), "needs"))
        assertEquals(
            listOf("build", "public-cdn"),
            sequence(mapping(jobs, "release-assets"), "needs"),
        )
        assertEquals("production", scalar(mapping(jobs, "publish"), "environment"))
        assertFalse(mapping(jobs, "build").containsKey("environment"))
        assertFalse(mapping(jobs, "public-cdn").containsKey("environment"))
        assertFalse(mapping(jobs, "release-assets").containsKey("environment"))
        assertEquals(
            mapOf("contents" to "read", "packages" to "write"),
            mapping(mapping(jobs, "publish"), "permissions"),
        )
        assertEquals(
            mapOf("contents" to "write"),
            mapping(mapping(jobs, "release-assets"), "permissions"),
        )
        listOf("build", "publish", "public-cdn", "release-assets").forEach { job ->
            steps(mapping(jobs, job))
                .filter { scalar(it, "uses") == "actions/checkout@v7" }
                .single()
                .let { assertEquals(false, mapping(it, "with")["persist-credentials"]) }
        }
        val publish = steps(mapping(jobs, "publish"))
        val gate = publish.indexOfFirst { scalar(it, "id") == "maven-gate" }
        assertTrue(gate >= 0)
        assertTrue(
            scalar(publish[gate], "run").contains("maven-create-or-compare.mjs") &&
                scalar(publish[gate], "run").contains("case \"\$decision\" in") &&
                scalar(publish[gate], "run").contains("decision=\$decision")
        )
        assertEquals(
            "Publish the unchanged Maven staging workspace",
            scalar(publish[gate + 1], "name"),
        )
        assertEquals(
            "steps.maven-gate.outputs.decision == 'publish'",
            scalar(publish[gate + 1], "if"),
        )
        assertTrue(
            scalar(publish[gate + 1], "run")
                .contains("publishMavenJavaPublicationToGitHubPackagesRepository")
        )
        assertTrue(scalar(publish[gate + 2], "run").contains("r2-create-or-compare.mjs"))
        val cdn = mapping(jobs, "public-cdn")
        assertFalse(
            cdn.toString().contains("secrets.") ||
                cdn.toString().contains("R2_") ||
                cdn.toString().contains("CLOUDFLARE")
        )
        assertTrue(steps(cdn).any { scalar(it, "run").contains("verify-cdn.mjs") })
    }

    private fun steps(job: Map<String, Any?>) = sequence(job, "steps").map(::mapping)

    private fun parse(name: String) = parseText(source(name))

    private fun source(name: String) = root.resolve(".github/workflows").resolve(name).readText()

    private fun parseText(text: String): Map<String, Any?> {
        val raw = Yaml().load<Any?>(text) as Map<*, *>
        return raw.entries.associate {
            (if (it.key == true) "on" else it.key.toString()) to it.value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapping(value: Any?): Map<String, Any?> =
        (value as Map<Any?, Any?>).entries.associate { it.key.toString() to it.value }

    private fun mapping(parent: Map<String, Any?>, key: String) =
        mapping(parent[key] ?: error("missing $key"))

    @Suppress("UNCHECKED_CAST")
    private fun sequence(parent: Map<String, Any?>, key: String): List<Any?> =
        parent[key] as? List<Any?> ?: error("missing list $key")

    private fun scalar(parent: Map<String, Any?>, key: String): String =
        parent[key]?.toString() ?: ""
}
