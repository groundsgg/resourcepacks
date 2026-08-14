package gg.grounds.resourcepacks.product

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestSemanticMatrixTest {
    @Test
    fun `every fixed semantic field has an independent exact diagnostic`() = withManifest { valid ->
        val otherUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val cases = mutableListOf<SemanticCase>()
        fun case(name: String, manifest: PackSetManifest, pointer: String, message: String) {
            cases += SemanticCase(name, manifest, listOf(invalid(pointer, message)))
        }
        case("schema", valid.copy(schemaVersion = 2), "/schemaVersion", "schemaVersion must be 1.")
        case("id", valid.copy(id = "grounds:other"), "/id", "id must be grounds:global.")
        case(
            "minecraft version",
            valid.copy(minecraft = valid.minecraft.copy(version = "26.1")),
            "/minecraft/version",
            "Minecraft version must be 26.2.",
        )
        case(
            "minecraft format",
            valid.copy(minecraft = valid.minecraft.copy(resourcePackFormat = 87)),
            "/minecraft/resourcePackFormat",
            "Resource pack format must be 88.",
        )
        case(
            "catalog id",
            valid.copy(catalog = valid.catalog.copy(id = "x:y")),
            "/catalog/id",
            "Catalog id mismatch.",
        )
        case(
            "catalog version",
            valid.copy(catalog = valid.catalog.copy(version = "9.9.9")),
            "/catalog/version",
            "Catalog version mismatch.",
        )
        case(
            "catalog coordinate",
            valid.copy(catalog = valid.catalog.copy(coordinate = "wrong")),
            "/catalog/coordinate",
            "Catalog coordinate mismatch.",
        )
        case(
            "catalog file",
            valid.copy(catalog = valid.catalog.copy(file = "wrong.jar")),
            "/catalog/file",
            "Catalog filename mismatch.",
        )
        for (index in valid.packs.indices) {
            val prefix = "/packs/$index"
            val pack = valid.packs[index]
            case(
                "pack $index order",
                valid.withPack(index, pack.copy(order = index + 2)),
                "$prefix/order",
                "Pack order mismatch.",
            )
            case(
                "pack $index role",
                valid.withPack(
                    index,
                    pack.copy(
                        role = "wrong",
                        url = "https://cdn.grounds.gg/resourcepacks/wrong/${pack.sha1}.zip",
                    ),
                ),
                "$prefix/role",
                "Pack role/order mismatch.",
            )
            case(
                "pack $index id",
                valid.withPack(index, pack.copy(id = "wrong")),
                "$prefix/id",
                "Pack id mismatch.",
            )
            case(
                "pack $index UUID",
                valid.withPack(index, pack.copy(uuid = otherUuid)),
                "$prefix/uuid",
                "Pack UUID mismatch.",
            )
            case(
                "pack $index required",
                valid.withPack(index, pack.copy(required = false)),
                "$prefix/required",
                "Pack must be required.",
            )
            case(
                "pack $index format",
                valid.withPack(index, pack.copy(resourcePackFormat = 87)),
                "$prefix/resourcePackFormat",
                "Format must be 88.",
            )
        }
        case(
            "repository",
            valid.copy(provenance = valid.provenance.copy(repository = "wrong/repo")),
            "/provenance/repository",
            "Repository mismatch.",
        )
        case(
            "commit",
            valid.copy(provenance = valid.provenance.copy(commit = "A".repeat(40))),
            "/provenance/commit",
            "Commit must be lowercase 40-hex.",
        )
        case(
            "tag",
            valid.copy(provenance = valid.provenance.copy(tag = "wrong")),
            "/provenance/tag",
            "Tag must equal v<version>.",
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                PackSetManifestJson.semanticProblems(case.manifest),
                case.name,
            )
        }
    }

    @Test
    fun `URL components and role path and filename SHA are each checked independently`() =
        withManifest { valid ->
            valid.packs.forEachIndexed { index, pack ->
                val sha = pack.sha1
                val otherRole = if (pack.role == "content") "platform" else "content"
                val cases =
                    listOf(
                        "http scheme" to
                            "http://cdn.grounds.gg/resourcepacks/${pack.role}/$sha.zip",
                        "userinfo" to
                            "https://user@cdn.grounds.gg/resourcepacks/${pack.role}/$sha.zip",
                        "explicit port" to
                            "https://cdn.grounds.gg:443/resourcepacks/${pack.role}/$sha.zip",
                        "wrong host" to "https://example.test/resourcepacks/${pack.role}/$sha.zip",
                        "query" to "https://cdn.grounds.gg/resourcepacks/${pack.role}/$sha.zip?x=1",
                        "fragment" to
                            "https://cdn.grounds.gg/resourcepacks/${pack.role}/$sha.zip#x",
                        "wrong role path" to
                            "https://cdn.grounds.gg/resourcepacks/$otherRole/$sha.zip",
                        "filename SHA mismatch" to
                            "https://cdn.grounds.gg/resourcepacks/${pack.role}/${"a".repeat(40)}.zip",
                    )
                cases.forEach { (name, url) ->
                    assertEquals(
                        listOf(invalid("/packs/$index/url", "Pack URL mismatch.")),
                        PackSetManifestJson.semanticProblems(
                            valid.withPack(index, pack.copy(url = url))
                        ),
                        "pack $index $name",
                    )
                }
            }
            assertEquals(
                emptyList(),
                PackSetManifestJson.semanticProblems(valid),
                "valid exact URL",
            )
        }

    @Test
    fun `SHA1 and SHA256 lexical branches are exact and independent for both packs`() =
        withManifest { valid ->
            val mutations =
                listOf(
                    "uppercase" to { value: String -> value.uppercase() },
                    "too short" to { value: String -> value.dropLast(1) },
                    "too long" to { value: String -> value + "a" },
                    "nonhex" to { value: String -> "g" + value.drop(1) },
                )
            valid.packs.forEachIndexed { index, pack ->
                mutations.forEach { (name, mutate) ->
                    val sha1 = mutate(pack.sha1)
                    assertEquals(
                        listOf(invalid("/packs/$index/sha1", "Hash must be lowercase 40-hex.")),
                        PackSetManifestJson.semanticProblems(
                            valid.withPack(
                                index,
                                pack.copy(
                                    sha1 = sha1,
                                    url =
                                        "https://cdn.grounds.gg/resourcepacks/${pack.role}/$sha1.zip",
                                ),
                            )
                        ),
                        "pack $index SHA1 $name",
                    )
                    assertEquals(
                        listOf(invalid("/packs/$index/sha256", "Hash must be lowercase 64-hex.")),
                        PackSetManifestJson.semanticProblems(
                            valid.withPack(index, pack.copy(sha256 = mutate(pack.sha256)))
                        ),
                        "pack $index SHA256 $name",
                    )
                }
            }
            mutations.forEach { (name, mutate) ->
                assertEquals(
                    listOf(invalid("/catalog/sha256", "Hash must be lowercase 64-hex.")),
                    PackSetManifestJson.semanticProblems(
                        valid.copy(
                            catalog = valid.catalog.copy(sha256 = mutate(valid.catalog.sha256))
                        )
                    ),
                    "catalog SHA256 $name",
                )
            }
            assertEquals(
                emptyList(),
                PackSetManifestJson.semanticProblems(valid),
                "valid lowercase hashes",
            )
        }

    @Test
    fun `size zero negative and positive long boundaries are independent`() =
        withManifest { valid ->
            val values =
                listOf(
                    0L to listOf(invalid("/catalog/size", "Size must be positive.")),
                    -1L to listOf(invalid("/catalog/size", "Size must be positive.")),
                    1L to emptyList(),
                    Long.MAX_VALUE to emptyList(),
                )
            values.forEach { (size, expected) ->
                assertEquals(
                    expected,
                    PackSetManifestJson.semanticProblems(
                        valid.copy(catalog = valid.catalog.copy(size = size))
                    ),
                    "catalog size $size",
                )
            }
            valid.packs.forEachIndexed { index, pack ->
                values.forEach { (size, catalogExpected) ->
                    val expected = catalogExpected.map { it.copy(pointer = "/packs/$index/size") }
                    assertEquals(
                        expected,
                        PackSetManifestJson.semanticProblems(
                            valid.withPack(index, pack.copy(size = size))
                        ),
                        "pack $index size $size",
                    )
                }
            }
        }

    @Test
    fun `SemVer prerelease build leading zeros and length are exact`() = withManifest { valid ->
        val cases =
            listOf(
                "1.2.3-alpha.1+build.7" to emptyList(),
                "01.2.3" to listOf(invalid("/version", "version must be strict SemVer.")),
                "1.02.3" to listOf(invalid("/version", "version must be strict SemVer.")),
                "1.2.03" to listOf(invalid("/version", "version must be strict SemVer.")),
                "1.2.3-01" to listOf(invalid("/version", "version must be strict SemVer.")),
                "1.2.3-alpha.01" to listOf(invalid("/version", "version must be strict SemVer.")),
                "1.2.3+${"a".repeat(251)}" to
                    listOf(invalid("/version", "version must be strict SemVer.")),
            )
        cases.forEach { (version, expected) ->
            assertEquals(
                expected,
                PackSetManifestJson.semanticProblems(valid.withVersion(version)),
                version,
            )
        }
    }

    @Test
    fun `duplicate role order and UUID branches are individually exact`() = withManifest { valid ->
        val first = valid.packs[0]
        val second = valid.packs[1]
        val cases =
            listOf(
                SemanticCase(
                    "duplicate role",
                    valid.withPack(
                        1,
                        second.copy(
                            role = first.role,
                            url =
                                "https://cdn.grounds.gg/resourcepacks/${first.role}/${second.sha1}.zip",
                        ),
                    ),
                    listOf(
                        invalid("/packs", "Duplicate roles."),
                        invalid("/packs/1/role", "Pack role/order mismatch."),
                    ),
                ),
                SemanticCase(
                    "duplicate order",
                    valid.withPack(1, second.copy(order = first.order)),
                    listOf(
                        invalid("/packs", "Duplicate orders."),
                        invalid("/packs/1/order", "Pack order mismatch."),
                    ),
                ),
                SemanticCase(
                    "duplicate UUID",
                    valid.withPack(1, second.copy(uuid = first.uuid)),
                    listOf(
                        invalid("/packs", "Duplicate UUIDs."),
                        invalid("/packs/1/uuid", "Pack UUID mismatch."),
                    ),
                ),
            )
        cases.forEach { case ->
            assertEquals(
                case.expected,
                PackSetManifestJson.semanticProblems(case.manifest),
                case.name,
            )
        }
    }

    @Test
    fun `pack count is exact and actual array index owns artifact diagnostics`() {
        withManifest { valid ->
            assertEquals(
                listOf(invalid("/packs", "Exactly two packs are required.")),
                PackSetManifestJson.semanticProblems(valid.copy(packs = valid.packs.dropLast(1))),
            )
        }

        val directory = Files.createTempDirectory("manifest-actual-index-")
        try {
            val artifacts = sampleArtifacts(directory)
            val valid = matchingManifest(artifacts)
            val reorderedIdentity = valid.withPack(0, valid.packs[0].copy(order = 1))
            Files.write(artifacts.packs.getValue(PackRole.CONTENT), byteArrayOf(99))

            assertEquals(
                listOf(
                    invalid("/packs", "Duplicate orders."),
                    ManifestProblem(
                        "/packs/0",
                        ManifestProblemCode.ARTIFACT_MISMATCH,
                        "Artifact bytes, hash, or size mismatch.",
                    ),
                    invalid("/packs/0/order", "Pack order mismatch."),
                ),
                PackSetManifestJson.decodeAndValidate(
                        PackSetManifestJson.encode(reorderedIdentity),
                        artifacts,
                    )
                    .problems,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class SemanticCase(
        val name: String,
        val manifest: PackSetManifest,
        val expected: List<ManifestProblem>,
    )
}

private inline fun withManifest(block: (PackSetManifest) -> Unit) {
    val directory = Files.createTempDirectory("manifest-semantic-")
    try {
        block(matchingManifest(sampleArtifacts(directory)))
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun PackSetManifest.withPack(index: Int, replacement: PackManifest) =
    copy(packs = packs.toMutableList().also { it[index] = replacement })

private fun PackSetManifest.withVersion(replacement: String) =
    copy(
        version = replacement,
        catalog =
            catalog.copy(
                version = replacement,
                coordinate = "gg.grounds:resourcepacks-catalog:$replacement",
                file = "grounds-resourcepacks-catalog-$replacement.jar",
            ),
        provenance = provenance.copy(tag = "v$replacement"),
    )

private fun invalid(pointer: String, message: String) =
    ManifestProblem(pointer, ManifestProblemCode.INVALID_VALUE, message)
