package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
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
    fun `rejects missing symlink nonregular and byte-replaced artifacts`() {
        val directory = Files.createTempDirectory("manifest-artifact-matrix-")
        try {
            val artifacts = sampleArtifacts(directory)
            val valid = matchingManifest(artifacts)
            Files.write(artifacts.packs.getValue(PackRole.CONTENT), byteArrayOf(99))
            assertFalse(
                PackSetManifestJson.decodeAndValidate(PackSetManifestJson.encode(valid), artifacts)
                    .isValid
            )

            Files.delete(artifacts.catalog)
            assertFalse(
                PackSetManifestJson.decodeAndValidate(PackSetManifestJson.encode(valid), artifacts)
                    .isValid
            )

            val directoryArtifact = directory.resolve("directory.zip")
            Files.createDirectory(directoryArtifact)
            val nonregular =
                artifacts.copy(packs = artifacts.packs + (PackRole.CONTENT to directoryArtifact))
            assertFalse(
                PackSetManifestJson.decodeAndValidate(PackSetManifestJson.encode(valid), nonregular)
                    .isValid
            )

            val target = directory.resolve("target.jar")
            Files.write(target, byteArrayOf(1))
            val symlink = directory.resolve("grounds-resourcepacks-catalog-0.1.0.jar")
            try {
                Files.createSymbolicLink(symlink, target.fileName)
                assertFalse(
                    PackSetManifestJson.decodeAndValidate(
                            PackSetManifestJson.encode(valid),
                            artifacts.copy(catalog = symlink),
                        )
                        .isValid
                )
            } catch (_: UnsupportedOperationException) {
                // Some CI file systems do not permit symlinks; the no-follow production path
                // remains covered above.
            }
        } finally {
            directory.toFile().deleteRecursively()
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
            ManifestValidationHooks.afterFirstArtifactChunk = {
                Files.move(
                    replacement,
                    artifacts.catalog,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            val result =
                PackSetManifestJson.decodeAndValidate(PackSetManifestJson.encode(valid), artifacts)
            assertTrue(
                result.problems.any {
                    it.pointer == "/catalog" && it.code == ManifestProblemCode.ARTIFACT_CHANGED
                }
            )
        } finally {
            ManifestValidationHooks.afterFirstArtifactChunk = {}
            directory.toFile().deleteRecursively()
        }
    }
}
