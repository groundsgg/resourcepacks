package gg.grounds.resourcepacks.product

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestBoundaryMatrixTest {
    @Test
    fun `parser limits accept the exact boundary and reject one over`() =
        withArtifacts { artifacts ->
            val cases =
                listOf(
                    ParserBoundary(
                        "document",
                        accepted = padDocument("{\"x\":1}", 1_048_576),
                        rejected = padDocument("{\"x\":1}", 1_048_577),
                        problem =
                            problem(
                                "",
                                ManifestProblemCode.MALFORMED_JSON,
                                "Input exceeds 1048576 bytes.",
                            ),
                    ),
                    ParserBoundary(
                        "depth",
                        accepted = nestedArrays(64),
                        rejected = nestedArrays(65),
                        problem =
                            problem(
                                "/",
                                ManifestProblemCode.MALFORMED_JSON,
                                "Maximum nesting depth exceeded.",
                            ),
                    ),
                    ParserBoundary(
                        "string",
                        accepted = "{\"x\":\"${"a".repeat(16_384)}\"}".toByteArray(),
                        rejected = "{\"x\":\"${"a".repeat(16_385)}\"}".toByteArray(),
                        problem =
                            problem(
                                "/",
                                ManifestProblemCode.MALFORMED_JSON,
                                "String exceeds 16384 characters.",
                            ),
                    ),
                    ParserBoundary(
                        "number",
                        accepted = "{\"x\":${"1".repeat(128)}}".toByteArray(),
                        rejected = "{\"x\":${"1".repeat(129)}}".toByteArray(),
                        problem =
                            problem(
                                "/",
                                ManifestProblemCode.MALFORMED_JSON,
                                "Number exceeds 128 characters.",
                            ),
                    ),
                )

            cases.forEach { case ->
                val accepted = PackSetManifestJson.decodeStructure(case.accepted)
                assertEquals(
                    emptyList(),
                    accepted.problems.filter { it.code == ManifestProblemCode.MALFORMED_JSON },
                    "${case.name} exact boundary must pass parsing: ${accepted.problems}",
                )
                assertEquals(
                    listOf(case.problem),
                    PackSetManifestJson.decodeStructure(case.rejected).problems,
                    case.name,
                )
            }
        }

    @Test
    fun `trailing input and hostile encodings have exact diagnostics`() =
        withArtifacts { artifacts ->
            val valid = PackSetManifestJson.encode(matchingManifest(artifacts))
            val json = valid.toString(StandardCharsets.UTF_8)
            val cases =
                listOf(
                    "trailing input" to
                        (valid + "true".toByteArray()) to
                        problem("/", ManifestProblemCode.MALFORMED_JSON, "Trailing JSON input."),
                    "UTF-8 BOM" to
                        (byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + valid) to
                        bomProblem(),
                    "UTF-16BE BOM" to
                        bomEncoded(json, StandardCharsets.UTF_16BE, 0xFE, 0xFF) to
                        bomProblem(),
                    "UTF-16LE BOM" to
                        bomEncoded(json, StandardCharsets.UTF_16LE, 0xFF, 0xFE) to
                        bomProblem(),
                    "UTF-32BE BOM" to utf32(json, ByteOrder.BIG_ENDIAN) to bomProblem(),
                    "UTF-32LE BOM" to utf32(json, ByteOrder.LITTLE_ENDIAN) to bomProblem(),
                    "malformed UTF-8" to
                        byteArrayOf(0xC3.toByte(), 0x28) to
                        problem("", ManifestProblemCode.MALFORMED_JSON, "Malformed UTF-8."),
                    "unpaired high surrogate" to
                        "{\"x\":\"\\uD800\"}".toByteArray() to
                        problem(
                            "/",
                            ManifestProblemCode.MALFORMED_JSON,
                            "Malformed or overlong string.",
                        ),
                    "unpaired low surrogate" to
                        "{\"x\":\"\\uDC00\"}".toByteArray() to
                        problem(
                            "/",
                            ManifestProblemCode.MALFORMED_JSON,
                            "Malformed or overlong string.",
                        ),
                )

            cases.forEach { nested ->
                val (namedBytes, expected) = nested
                val (name, bytes) = namedBytes
                assertEquals(
                    listOf(expected),
                    PackSetManifestJson.decodeStructure(bytes).problems,
                    name,
                )
            }
        }

    @Test
    fun `fractional and beyond Long sizes are exact integer binding failures`() =
        withArtifacts { artifacts ->
            val canonical =
                PackSetManifestJson.encode(matchingManifest(artifacts))
                    .toString(StandardCharsets.UTF_8)
            val cases =
                listOf(
                    Triple("catalog fractional", "\"size\": 3", "\"size\": 1.5") to "/catalog/size",
                    Triple("catalog over Long", "\"size\": 3", "\"size\": 9223372036854775808") to
                        "/catalog/size",
                    Triple("pack 0 fractional", "\"size\": 4", "\"size\": 1.5") to "/packs/0/size",
                    Triple("pack 0 over Long", "\"size\": 4", "\"size\": 9223372036854775808") to
                        "/packs/0/size",
                    Triple("pack 1 fractional", "\"size\": 5", "\"size\": 1.5") to "/packs/1/size",
                    Triple("pack 1 over Long", "\"size\": 5", "\"size\": 9223372036854775808") to
                        "/packs/1/size",
                )
            cases.forEach { (mutation, pointer) ->
                val (name, original, replacement) = mutation
                assertEquals(
                    listOf(
                        problem(pointer, ManifestProblemCode.WRONG_TYPE, "Expected exact integer.")
                    ),
                    PackSetManifestJson.decodeStructure(
                            canonical.replaceFirst(original, replacement).toByteArray()
                        )
                        .problems,
                    name,
                )
            }
        }

    private data class ParserBoundary(
        val name: String,
        val accepted: ByteArray,
        val rejected: ByteArray,
        val problem: ManifestProblem,
    )

    private fun padDocument(json: String, size: Int): ByteArray =
        (json + " ".repeat(size - json.length)).toByteArray(StandardCharsets.UTF_8)

    private fun nestedArrays(depth: Int): ByteArray =
        ("[".repeat(depth) + "true" + "]".repeat(depth)).toByteArray()

    private fun bomProblem() =
        problem("", ManifestProblemCode.MALFORMED_JSON, "Byte-order marks are not permitted.")

    private fun bomEncoded(json: String, charset: java.nio.charset.Charset, vararg bom: Int) =
        bom.map(Int::toByte).toByteArray() + json.toByteArray(charset)

    private fun utf32(json: String, order: ByteOrder): ByteArray {
        val bom =
            if (order == ByteOrder.BIG_ENDIAN) byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte())
            else byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)
        val encoded = ByteBuffer.allocate(json.codePointCount(0, json.length) * 4).order(order)
        json.codePoints().forEach(encoded::putInt)
        return bom + encoded.array()
    }
}

private inline fun withArtifacts(block: (ManifestArtifacts) -> Unit) {
    val directory = Files.createTempDirectory("manifest-boundary-")
    try {
        block(sampleArtifacts(directory))
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun problem(pointer: String, code: ManifestProblemCode, message: String) =
    ManifestProblem(pointer, code, message)
