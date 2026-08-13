package gg.grounds.resourcepacks.product

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationContractTest {
    private val root: Path =
        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    @Test
    fun `automation files define the immutable packset release contract`() {
        val ci = workflow("ci.yml")
        val releasePlease = workflow("release-please.yml")
        val release = workflow("release.yml")

        assertEquals("CI", ci.requireScalar("name"))
        assertTrue(ci.hasSequenceValue("on.push.branches", "main"))
        assertTrue(ci.hasKey("on.pull_request"))
        assertEquals("read", ci.requireScalar("permissions.contents"))
        assertFalse(ci.hasKey("env"))
        assertFalse(ci.allScalars().any { it.contains("secrets.") })
        assertTrue(ci.allScalars().any { it == "25" })
        assertTrue(ci.allScalars().any { it == "24" })
        assertTrue(ci.allScalars().any { it.contains("version.txt") })
        assertTrue(ci.allScalars().any { it.contains("buildPackSet") })

        assertEquals("Release Please", releasePlease.requireScalar("name"))
        assertEquals("write", releasePlease.requireScalar("permissions.contents"))
        assertTrue(
            releasePlease.allScalars().any {
                it.contains("groundsgg/.github/.github/workflows/release-please.yml@main")
            }
        )
        assertTrue(releasePlease.allScalars().any { it.trim() == "inherit" })

        assertTrue(release.hasSequenceValue("on.push.tags", "v*"))
        assertEquals("false", release.requireScalar("concurrency.cancel-in-progress"))
        assertEquals("production", release.requireScalar("jobs.publish.environment"))
        assertEquals("write", release.requireScalar("permissions.contents"))
        assertEquals("write", release.requireScalar("permissions.packages"))
        assertTrue(release.hasKey("jobs.public-cdn.needs"))
        assertFalse(
            release.allScalars().any {
                it.contains("rm -rf") || it.contains("--clobber") || it.contains("delete-object")
            }
        )

        val releaseSource = root.resolve(".github/workflows/release.yml").readText()
        val gate = releaseSource.indexOf("id: maven-gate")
        val publish = releaseSource.indexOf("name: Publish the unchanged Maven staging workspace")
        val r2 = releaseSource.indexOf("name: Create or compare immutable R2 ZIP objects")
        assertTrue(gate >= 0 && publish > gate && r2 > publish)
        assertEquals(
            0,
            Regex("\\n\\s*- name:").findAll(releaseSource.substring(gate, publish)).count(),
            "Maven gate must directly precede publication",
        )
        listOf(
                "grounds-content-",
                "grounds-platform-",
                "grounds-resourcepacks-catalog-",
                "manifest.json",
            )
            .forEach { assertTrue(root.resolve("README.md").readText().contains(it)) }

        val config = root.resolve("release-please-config.json").readText()
        val manifest = root.resolve(".release-please-manifest.json").readText()
        assertTrue(config.contains("\"release-type\": \"simple\""))
        assertTrue(config.contains("\"version-file\": \"version.txt\""))
        assertTrue(config.contains("\"initial-version\": \"0.1.0\""))
        assertEquals("{}", manifest.trim())
    }

    private fun workflow(name: String): YamlDocument =
        YamlDocument.parse(root.resolve(".github/workflows").resolve(name).readText())
}

/** Small structured YAML reader for the restricted workflow subset used by this contract. */
private class YamlDocument private constructor(private val values: Map<String, List<String>>) {
    fun requireScalar(path: String): String =
        values[path]?.singleOrNull() ?: error("Missing scalar $path: $values")

    fun hasKey(path: String): Boolean = path in values

    fun hasSequenceValue(path: String, value: String): Boolean =
        values[path]?.any { it == value } == true

    fun allScalars(): List<String> = values.values.flatten()

    companion object {
        fun parse(input: String): YamlDocument {
            val values = linkedMapOf<String, MutableList<String>>()
            val parents = mutableListOf<Pair<Int, String>>()
            input.lineSequence().forEach { raw ->
                if (raw.isBlank() || raw.trimStart().startsWith("#")) return@forEach
                val indent = raw.indexOfFirst { !it.isWhitespace() }
                val text = raw.trim()
                while (parents.isNotEmpty() && parents.last().first >= indent) parents.removeLast()
                val parent = parents.joinToString(".") { it.second }
                if (text.startsWith("- ")) {
                    values
                        .getOrPut(parent) { mutableListOf() }
                        .add(text.removePrefix("- ").trim('"', '\''))
                } else {
                    val separator = text.indexOf(":")
                    if (separator > 0) {
                        val key = text.substring(0, separator).trim('"', '\'')
                        val path = listOf(parent, key).filter { it.isNotEmpty() }.joinToString(".")
                        val value = text.substring(separator + 1).trim()
                        values.getOrPut(path) { mutableListOf() }
                        if (value.startsWith("[") && value.endsWith("]")) {
                            value
                                .removePrefix("[")
                                .removeSuffix("]")
                                .split(',')
                                .map { it.trim().trim('"', '\'') }
                                .filter { it.isNotEmpty() }
                                .forEach(values.getValue(path)::add)
                        } else if (value.isNotEmpty()) {
                            values.getValue(path).add(value.trim('"', '\''))
                        }
                        parents.add(indent to key)
                    } else if (parent.isNotEmpty()) {
                        values.getOrPut(parent) { mutableListOf() }.add(text)
                    }
                }
            }
            return YamlDocument(values)
        }
    }
}
