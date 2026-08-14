package gg.grounds.resourcepacks.product

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestMutationTest {
    @Test
    fun `rejects malformed and hostile JSON without leaking parser exceptions`() {
        val directory = Files.createTempDirectory("manifest-mut-")
        try {
            val artifacts = sampleArtifacts(directory)
            listOf(
                    "{\"schemaVersion\":1,\"schemaVersion\":1}",
                    "{\"unknown\":true}",
                    "[]",
                    "{\"schemaVersion\":1} trailing",
                    "{\"x\":\"\\uD800\"}",
                    "[".repeat(80) + "]".repeat(80),
                )
                .forEach { bytes ->
                    val result =
                        PackSetManifestJson.decodeAndValidate(bytes.toByteArray(), artifacts)
                    assertFalse(result.isValid)
                    assertTrue(result.problems.isNotEmpty())
                }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `duplicate and unknown keys retain RFC6901 contextual pointers`() {
        val directory = Files.createTempDirectory("manifest-pointer-")
        try {
            val artifacts = sampleArtifacts(directory)
            val duplicate =
                PackSetManifestJson.decodeAndValidate(
                    "{\"minecraft\":{\"x\":1,\"x\":2}}".toByteArray(),
                    artifacts,
                )
            assertTrue(
                duplicate.problems.single().pointer == "/minecraft",
                duplicate.problems.toString(),
            )
            assertTrue(duplicate.problems.single().code == ManifestProblemCode.DUPLICATE_KEY)
            val unknown =
                PackSetManifestJson.decodeAndValidate(
                    "{\"a/b\":1,\"a~b\":2}".toByteArray(),
                    artifacts,
                )
            assertTrue(
                unknown.problems.any {
                    it.pointer == "/a~1b" && it.code == ManifestProblemCode.UNKNOWN_FIELD
                }
            )
            assertTrue(
                unknown.problems.any {
                    it.pointer == "/a~0b" && it.code == ManifestProblemCode.UNKNOWN_FIELD
                }
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `duplicate pointers cover each schema object`() {
        val directory = Files.createTempDirectory("manifest-duplicate-pointers-")
        try {
            val artifacts = sampleArtifacts(directory)
            val cases =
                listOf(
                    "{\"x\":1,\"x\":2}" to "/",
                    "{\"minecraft\":{\"x\":1,\"x\":2}}" to "/minecraft",
                    "{\"catalog\":{\"x\":1,\"x\":2}}" to "/catalog",
                    "{\"packs\":[{\"x\":1,\"x\":2}]}" to "/packs/0",
                    "{\"packs\":[{}, {\"x\":1,\"x\":2}]}" to "/packs/1",
                    "{\"provenance\":{\"x\":1,\"x\":2}}" to "/provenance",
                )
            cases.forEach { (json, pointer) ->
                assertEquals(
                    listOf(
                        ManifestProblem(
                            pointer,
                            ManifestProblemCode.DUPLICATE_KEY,
                            "Duplicate key.",
                        )
                    ),
                    PackSetManifestJson.decodeAndValidate(json.toByteArray(), artifacts).problems,
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `strict parser matrix has stable diagnostic codes pointers and messages`() {
        val directory = Files.createTempDirectory("manifest-parser-matrix-")
        try {
            val artifacts = sampleArtifacts(directory)
            val cases =
                listOf(
                    Triple(
                        "{\"schemaVersion\":1,\"schemaVersion\":2}",
                        "/",
                        ManifestProblemCode.DUPLICATE_KEY,
                    ),
                    Triple(
                        "{\"minecraft\":{\"a/b\":1}}",
                        "/minecraft/a~1b",
                        ManifestProblemCode.UNKNOWN_FIELD,
                    ),
                    Triple(
                        "{\"catalog\":{\"a~b\":1}}",
                        "/catalog/a~0b",
                        ManifestProblemCode.UNKNOWN_FIELD,
                    ),
                    Triple("[]", "", ManifestProblemCode.WRONG_TYPE),
                    Triple("{\"packs\":null}", "/packs", ManifestProblemCode.WRONG_TYPE),
                    Triple(
                        "{\"schemaVersion\":1} trailing",
                        "/",
                        ManifestProblemCode.MALFORMED_JSON,
                    ),
                )
            cases.forEach { (input, pointer, code) ->
                val result = PackSetManifestJson.decodeAndValidate(input.toByteArray(), artifacts)
                assertTrue(
                    result.problems.any { it.pointer == pointer && it.code == code },
                    result.problems.toString(),
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `semantic matrix covers SemVer sizes hashes URLs and duplicate identities`() {
        val directory = Files.createTempDirectory("manifest-semantic-matrix-")
        try {
            val artifacts = sampleArtifacts(directory)
            val valid = matchingManifest(artifacts)
            val versions =
                listOf(
                    "1.2.3-alpha.1+build.7" to true,
                    "1.2.3-01" to false,
                    "1.2.3-alpha.01" to false,
                    "01.2.3" to false,
                )
            versions.forEach { (version, accepted) ->
                val result =
                    PackSetManifestJson.decodeAndValidate(
                        PackSetManifestJson.encode(
                            valid.copy(
                                version = version,
                                catalog =
                                    valid.catalog.copy(
                                        version = version,
                                        coordinate = "gg.grounds:resourcepacks-catalog:$version",
                                        file = "grounds-resourcepacks-catalog-$version.jar",
                                    ),
                                provenance = valid.provenance.copy(tag = "v$version"),
                            )
                        ),
                        artifacts,
                    )
                assertEquals(
                    accepted,
                    result.problems.none { it.pointer == "/version" },
                    result.problems.toString(),
                )
            }
            val broken =
                valid.copy(
                    packs =
                        valid.packs.map {
                            it.copy(
                                url = "http://user@cdn.grounds.gg:443/bad?x#f",
                                sha1 = "A",
                                sha256 = "z",
                                size = -1,
                            )
                        }
                )
            val problems =
                PackSetManifestJson.decodeAndValidate(PackSetManifestJson.encode(broken), artifacts)
                    .problems
            valid.packs.indices.forEach { index ->
                assertTrue(
                    problems.any {
                        it.pointer == "/packs/$index/url" &&
                            it.code == ManifestProblemCode.INVALID_VALUE
                    }
                )
                assertTrue(
                    problems.any {
                        it.pointer == "/packs/$index/sha1" &&
                            it.code == ManifestProblemCode.INVALID_VALUE
                    }
                )
                assertTrue(
                    problems.any {
                        it.pointer == "/packs/$index/sha256" &&
                            it.code == ManifestProblemCode.INVALID_VALUE
                    }
                )
                assertTrue(
                    problems.any {
                        it.pointer == "/packs/$index/size" &&
                            it.code == ManifestProblemCode.INVALID_VALUE
                    }
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `independent identity and catalog branches have exact diagnostics`() {
        val directory = Files.createTempDirectory("manifest-exact-branches-")
        try {
            val artifacts = sampleArtifacts(directory)
            val valid = matchingManifest(artifacts)
            val cases =
                listOf(
                    valid.copy(schemaVersion = 2) to
                        ManifestProblem(
                            "/schemaVersion",
                            ManifestProblemCode.INVALID_VALUE,
                            "schemaVersion must be 1.",
                        ),
                    valid.copy(id = "other") to
                        ManifestProblem(
                            "/id",
                            ManifestProblemCode.INVALID_VALUE,
                            "id must be grounds:global.",
                        ),
                    valid.copy(minecraft = valid.minecraft.copy(version = "x")) to
                        ManifestProblem(
                            "/minecraft/version",
                            ManifestProblemCode.INVALID_VALUE,
                            "Minecraft version must be 26.2.",
                        ),
                    valid.copy(minecraft = valid.minecraft.copy(resourcePackFormat = 1)) to
                        ManifestProblem(
                            "/minecraft/resourcePackFormat",
                            ManifestProblemCode.INVALID_VALUE,
                            "Resource pack format must be 88.",
                        ),
                    valid.copy(catalog = valid.catalog.copy(id = "x")) to
                        ManifestProblem(
                            "/catalog/id",
                            ManifestProblemCode.INVALID_VALUE,
                            "Catalog id mismatch.",
                        ),
                    valid.copy(provenance = valid.provenance.copy(repository = "x")) to
                        ManifestProblem(
                            "/provenance/repository",
                            ManifestProblemCode.INVALID_VALUE,
                            "Repository mismatch.",
                        ),
                )
            cases.forEach { (manifest, expected) ->
                val result =
                    PackSetManifestJson.decodeAndValidate(
                        PackSetManifestJson.encode(manifest),
                        artifacts,
                    )
                assertTrue(expected in result.problems, result.problems.toString())
            }
            val duplicateRole =
                valid.copy(
                    packs =
                        listOf(
                            valid.packs[0],
                            valid.packs[0].copy(
                                order = 1,
                                id = "grounds-platform",
                                uuid = PackSetConstants.platformUuid,
                            ),
                        )
                )
            assertTrue(
                ManifestProblem("/packs", ManifestProblemCode.INVALID_VALUE, "Duplicate roles.") in
                    PackSetManifestJson.decodeAndValidate(
                            PackSetManifestJson.encode(duplicateRole),
                            artifacts,
                        )
                        .problems
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports independent schema and artifact mismatches deterministically`() {
        val directory = Files.createTempDirectory("manifest-mismatch-")
        try {
            val artifacts = sampleArtifacts(directory)
            val mutated =
                sampleManifest()
                    .copy(
                        version = "01.0.0",
                        catalog = sampleManifest().catalog.copy(size = 0, sha256 = "A".repeat(64)),
                        packs =
                            sampleManifest().packs.reversed().map {
                                it.copy(required = false, size = -1, url = "http://evil.example")
                            },
                        provenance =
                            sampleManifest().provenance.copy(commit = "F".repeat(40), tag = "wrong"),
                    )
            val result =
                PackSetManifestJson.decodeAndValidate(
                    PackSetManifestJson.encode(mutated),
                    artifacts,
                )

            assertFalse(result.isValid)
            assertTrue(result.problems.map { it.pointer }.distinct().size >= 8)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects every externally mutable contract field`() {
        val directory = Files.createTempDirectory("manifest-field-matrix-")
        try {
            val artifacts = sampleArtifacts(directory)
            val valid = matchingManifest(artifacts)
            val mutations =
                listOf<PackSetManifest>(
                    valid.copy(schemaVersion = 2),
                    valid.copy(id = "grounds:other"),
                    valid.copy(version = "1.0"),
                    valid.copy(
                        minecraft = valid.minecraft.copy(version = "26.1", resourcePackFormat = 87)
                    ),
                    valid.copy(
                        catalog =
                            valid.catalog.copy(
                                id = "x:y",
                                coordinate = "bad",
                                file = "bad.jar",
                                size = 0,
                            )
                    ),
                    valid.copy(packs = valid.packs.dropLast(1)),
                    valid.copy(
                        packs =
                            valid.packs.map {
                                it.copy(
                                    sha1 = it.sha1.uppercase(),
                                    sha256 = "x",
                                    size = 0,
                                    url =
                                        "https://cdn.grounds.gg:443/resourcepacks/x/${it.sha1}.zip?x#y",
                                )
                            }
                    ),
                    valid.copy(packs = valid.packs.reversed()),
                    valid.copy(
                        provenance =
                            valid.provenance.copy(
                                repository = "grounds/repo",
                                commit = "A".repeat(40),
                                tag = "v9.9.9",
                            )
                    ),
                )
            mutations.forEach { mutation ->
                assertFalse(
                    PackSetManifestJson.decodeAndValidate(
                            PackSetManifestJson.encode(mutation),
                            artifacts,
                        )
                        .isValid
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing catalog and each missing pack have isolated exact diagnostics`() {
        withArtifactFixture("missing-catalog") { _, artifacts, valid ->
            Files.delete(artifacts.catalog)
            assertArtifactProblems(
                valid,
                artifacts,
                artifactProblem(
                    "/catalog",
                    ManifestProblemCode.ARTIFACT_MISSING,
                    "Artifact missing.",
                ),
            )
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("missing-${role.name.lowercase()}") { directory, artifacts, valid ->
                val missing = directory.resolve("missing-${role.name.lowercase()}.zip")
                assertArtifactProblems(
                    valid,
                    artifacts.copy(packs = artifacts.packs + (role to missing)),
                    artifactProblem(
                        "/packs/$index",
                        ManifestProblemCode.ARTIFACT_MISSING,
                        "Artifact missing.",
                    ),
                )
            }
        }
    }

    @Test
    fun `directory catalog and packs have isolated exact nonregular diagnostics`() {
        withArtifactFixture("directory-catalog") { _, artifacts, valid ->
            Files.delete(artifacts.catalog)
            Files.createDirectory(artifacts.catalog)
            assertArtifactProblems(
                valid,
                artifacts,
                notRegularProblem("/catalog", artifacts.catalog),
            )
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("directory-${role.name.lowercase()}") { _, artifacts, valid ->
                val path = artifacts.packs.getValue(role)
                Files.delete(path)
                Files.createDirectory(path)
                assertArtifactProblems(valid, artifacts, notRegularProblem("/packs/$index", path))
            }
        }
    }

    @Test
    fun `symlink catalog and packs are exercised with isolated exact nonregular diagnostics`() {
        withArtifactFixture("symlink-catalog") { directory, artifacts, valid ->
            val target = Files.write(directory.resolve("catalog-target.jar"), byteArrayOf(1, 2, 3))
            Files.delete(artifacts.catalog)
            Files.createSymbolicLink(artifacts.catalog, target.fileName)
            assertArtifactProblems(
                valid,
                artifacts,
                notRegularProblem("/catalog", artifacts.catalog),
            )
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("symlink-${role.name.lowercase()}") { directory, artifacts, valid ->
                val path = artifacts.packs.getValue(role)
                val target =
                    Files.write(
                        directory.resolve("${role.name.lowercase()}-target.zip"),
                        Files.readAllBytes(path),
                    )
                Files.delete(path)
                Files.createSymbolicLink(path, target.fileName)
                assertArtifactProblems(valid, artifacts, notRegularProblem("/packs/$index", path))
            }
        }
    }

    @Test
    fun `artifact filename mismatches are isolated from byte validation`() {
        withArtifactFixture("filename-catalog") { directory, artifacts, valid ->
            val renamed = directory.resolve("wrong-catalog-name.jar")
            Files.move(artifacts.catalog, renamed)
            assertArtifactProblems(
                valid,
                artifacts.copy(catalog = renamed),
                artifactProblem(
                    "/catalog/file",
                    ManifestProblemCode.ARTIFACT_MISMATCH,
                    "Artifact filename mismatch.",
                ),
            )
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("filename-${role.name.lowercase()}") { directory, artifacts, valid
                ->
                val original = artifacts.packs.getValue(role)
                val renamed = directory.resolve("wrong-${role.name.lowercase()}.zip")
                Files.move(original, renamed)
                assertArtifactProblems(
                    valid,
                    artifacts.copy(packs = artifacts.packs + (role to renamed)),
                    artifactProblem(
                        "/packs/$index/file",
                        ManifestProblemCode.ARTIFACT_MISMATCH,
                        "Artifact filename mismatch.",
                    ),
                )
            }
        }
    }

    @Test
    fun `same-size final byte mismatches are isolated for catalog and packs`() {
        withArtifactFixture("bytes-catalog") { _, artifacts, valid ->
            overwriteWithDifferentSameSizeBytes(artifacts.catalog)
            assertArtifactProblems(valid, artifacts, mismatchProblem("/catalog"))
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("bytes-${role.name.lowercase()}") { _, artifacts, valid ->
                overwriteWithDifferentSameSizeBytes(artifacts.packs.getValue(role))
                assertArtifactProblems(valid, artifacts, mismatchProblem("/packs/$index"))
            }
        }
    }

    @Test
    fun `manifest hash mismatches are isolated for catalog and every pack hash field`() {
        withArtifactFixture("hash-catalog") { _, artifacts, valid ->
            val changed = valid.copy(catalog = valid.catalog.copy(sha256 = "a".repeat(64)))
            assertArtifactProblems(changed, artifacts, mismatchProblem("/catalog"))
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("sha256-${role.name.lowercase()}") { _, artifacts, valid ->
                val pack = valid.packs[index]
                val changed = valid.withArtifactPack(index, pack.copy(sha256 = "a".repeat(64)))
                assertArtifactProblems(changed, artifacts, mismatchProblem("/packs/$index"))
            }
            withArtifactFixture("sha1-${role.name.lowercase()}") { directory, artifacts, valid ->
                val pack = valid.packs[index]
                val sha1 = "a".repeat(40)
                val renamed = directory.resolve("$sha1.zip")
                Files.move(artifacts.packs.getValue(role), renamed)
                val changed =
                    valid.withArtifactPack(
                        index,
                        pack.copy(
                            sha1 = sha1,
                            url = "https://cdn.grounds.gg/resourcepacks/${pack.role}/$sha1.zip",
                        ),
                    )
                assertArtifactProblems(
                    changed,
                    artifacts.copy(packs = artifacts.packs + (role to renamed)),
                    mismatchProblem("/packs/$index"),
                )
            }
        }
    }

    @Test
    fun `manifest size mismatches are isolated for catalog and packs`() {
        withArtifactFixture("size-catalog") { _, artifacts, valid ->
            val changed = valid.copy(catalog = valid.catalog.copy(size = valid.catalog.size + 1))
            assertArtifactProblems(changed, artifacts, mismatchProblem("/catalog"))
        }
        PackRole.entries.forEachIndexed { index, role ->
            withArtifactFixture("size-${role.name.lowercase()}") { _, artifacts, valid ->
                val pack = valid.packs[index]
                val changed = valid.withArtifactPack(index, pack.copy(size = pack.size + 1))
                assertArtifactProblems(changed, artifacts, mismatchProblem("/packs/$index"))
            }
        }
    }

    @Test
    fun `detects a deterministic artifact replacement while streaming`() {
        val directory = Files.createTempDirectory("manifest-artifact-race-")
        try {
            val artifacts = sampleArtifacts(directory)
            Files.write(artifacts.catalog, ByteArray(70_000) { 7 })
            val valid = matchingManifest(artifacts)
            val replacement = directory.resolve("replacement.jar")
            Files.write(replacement, ByteArray(70_000) { 8 })
            val result =
                PackSetManifestJson.decodeAndValidate(
                    PackSetManifestJson.encode(valid),
                    artifacts,
                ) {
                    Files.move(
                        replacement,
                        artifacts.catalog,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            assertEquals(
                listOf(
                    artifactProblem(
                        "/catalog",
                        ManifestProblemCode.ARTIFACT_CHANGED,
                        "Artifact changed while hashing: ${artifacts.catalog}",
                    )
                ),
                result.problems,
            )
        } finally {
            deleteTreeNoFollow(directory)
        }
    }
}

private inline fun withArtifactFixture(
    name: String,
    block: (Path, ManifestArtifacts, PackSetManifest) -> Unit,
) {
    val directory = Files.createTempDirectory("manifest-artifact-$name-")
    try {
        val artifacts = sampleArtifacts(directory)
        val valid = matchingManifest(artifacts)
        assertArtifactProblems(valid, artifacts)
        block(directory, artifacts, valid)
    } finally {
        deleteTreeNoFollow(directory)
    }
}

private fun assertArtifactProblems(
    manifest: PackSetManifest,
    artifacts: ManifestArtifacts,
    vararg expected: ManifestProblem,
) {
    assertEquals(
        expected.toList(),
        PackSetManifestJson.decodeAndValidate(PackSetManifestJson.encode(manifest), artifacts)
            .problems,
    )
}

private fun artifactProblem(pointer: String, code: ManifestProblemCode, message: String) =
    ManifestProblem(pointer, code, message)

private fun mismatchProblem(pointer: String) =
    artifactProblem(
        pointer,
        ManifestProblemCode.ARTIFACT_MISMATCH,
        "Artifact bytes, hash, or size mismatch.",
    )

private fun notRegularProblem(pointer: String, path: Path) =
    artifactProblem(
        pointer,
        ManifestProblemCode.ARTIFACT_NOT_REGULAR,
        "Artifact is not a regular file: $path",
    )

private fun overwriteWithDifferentSameSizeBytes(path: Path) {
    val size = Files.size(path).toInt()
    Files.write(path, ByteArray(size) { 0x5A })
}

private fun PackSetManifest.withArtifactPack(index: Int, replacement: PackManifest) =
    copy(packs = packs.toMutableList().also { it[index] = replacement })

private fun deleteTreeNoFollow(root: Path) {
    if (!Files.exists(root)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                failure: java.io.IOException?,
            ): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
