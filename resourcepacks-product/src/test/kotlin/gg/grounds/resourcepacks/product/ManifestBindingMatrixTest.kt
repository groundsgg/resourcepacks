package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestBindingMatrixTest {
    @Test
    fun `missing null and wrong types cover every binding helper at every contextual pointer`() {
        val directory = Files.createTempDirectory("manifest-binding-")
        try {
            val baseline = manifestObject(matchingManifest(sampleArtifacts(directory)))
            bindingFields().forEach { field ->
                val missing = deepCopy(baseline)
                parent(missing, field.path).remove(field.path.last() as String)
                assertEquals(
                    field.missingProblems(),
                    PackSetManifestJson.decodeStructure(json(missing).toByteArray()).problems,
                    "missing ${field.pointer}",
                )

                listOf("null" to null, "wrong type" to field.kind.wrongValue).forEach {
                    (name, value) ->
                    val changed = deepCopy(baseline)
                    parent(changed, field.path)[field.path.last() as String] = value
                    assertEquals(
                        field.wrongProblems(),
                        PackSetManifestJson.decodeStructure(json(changed).toByteArray()).problems,
                        "$name ${field.pointer}",
                    )
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown keys are rejected independently in every schema object`() {
        val directory = Files.createTempDirectory("manifest-unknown-")
        try {
            val baseline = manifestObject(matchingManifest(sampleArtifacts(directory)))
            val cases =
                listOf(
                    emptyList<Any>() to "/unexpected",
                    listOf("minecraft") to "/minecraft/unexpected",
                    listOf("catalog") to "/catalog/unexpected",
                    listOf("packs", 0) to "/packs/0/unexpected",
                    listOf("packs", 1) to "/packs/1/unexpected",
                    listOf("provenance") to "/provenance/unexpected",
                )
            cases.forEach { (path, pointer) ->
                val changed = deepCopy(baseline)
                objectAt(changed, path)["unexpected"] = true
                assertEquals(
                    listOf(problem(pointer, ManifestProblemCode.UNKNOWN_FIELD, "Unknown field.")),
                    PackSetManifestJson.decodeStructure(json(changed).toByteArray()).problems,
                    pointer,
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private enum class Kind(val message: String, val wrongValue: Any?) {
        OBJECT("Expected object.", "wrong"),
        ARRAY("Expected array.", "wrong"),
        STRING("Expected string.", false),
        BOOLEAN("Expected boolean.", "wrong"),
        INTEGER("Expected exact integer.", "wrong"),
        UUID("Expected string.", false),
    }

    private data class BindingField(val path: List<Any>, val kind: Kind) {
        val pointer = "/" + path.joinToString("/")

        fun missingProblems(): List<ManifestProblem> = buildList {
            add(problem(pointer, ManifestProblemCode.MISSING_FIELD, "Missing field."))
            if (kind != Kind.OBJECT) {
                add(problem(pointer, ManifestProblemCode.WRONG_TYPE, kind.message))
            }
            if (kind == Kind.UUID) {
                add(problem(pointer, ManifestProblemCode.INVALID_VALUE, "Invalid UUID."))
            }
        }

        fun wrongProblems(): List<ManifestProblem> = buildList {
            add(problem(pointer, ManifestProblemCode.WRONG_TYPE, kind.message))
            if (kind == Kind.UUID) {
                add(problem(pointer, ManifestProblemCode.INVALID_VALUE, "Invalid UUID."))
            }
        }
    }

    private fun bindingFields(): List<BindingField> =
        listOf(
            BindingField(listOf("schemaVersion"), Kind.INTEGER),
            BindingField(listOf("id"), Kind.STRING),
            BindingField(listOf("version"), Kind.STRING),
            BindingField(listOf("minecraft"), Kind.OBJECT),
            BindingField(listOf("minecraft", "version"), Kind.STRING),
            BindingField(listOf("minecraft", "resourcePackFormat"), Kind.INTEGER),
            BindingField(listOf("catalog"), Kind.OBJECT),
            BindingField(listOf("catalog", "id"), Kind.STRING),
            BindingField(listOf("catalog", "version"), Kind.STRING),
            BindingField(listOf("catalog", "coordinate"), Kind.STRING),
            BindingField(listOf("catalog", "file"), Kind.STRING),
            BindingField(listOf("catalog", "sha256"), Kind.STRING),
            BindingField(listOf("catalog", "size"), Kind.INTEGER),
            BindingField(listOf("packs"), Kind.ARRAY),
        ) +
            listOf(0, 1).flatMap { index ->
                listOf(
                    BindingField(listOf("packs", index, "order"), Kind.INTEGER),
                    BindingField(listOf("packs", index, "role"), Kind.STRING),
                    BindingField(listOf("packs", index, "id"), Kind.STRING),
                    BindingField(listOf("packs", index, "uuid"), Kind.UUID),
                    BindingField(listOf("packs", index, "required"), Kind.BOOLEAN),
                    BindingField(listOf("packs", index, "url"), Kind.STRING),
                    BindingField(listOf("packs", index, "sha1"), Kind.STRING),
                    BindingField(listOf("packs", index, "sha256"), Kind.STRING),
                    BindingField(listOf("packs", index, "size"), Kind.INTEGER),
                    BindingField(listOf("packs", index, "resourcePackFormat"), Kind.INTEGER),
                )
            } +
            listOf(
                BindingField(listOf("provenance"), Kind.OBJECT),
                BindingField(listOf("provenance", "repository"), Kind.STRING),
                BindingField(listOf("provenance", "commit"), Kind.STRING),
                BindingField(listOf("provenance", "tag"), Kind.STRING),
            )

    private fun manifestObject(manifest: PackSetManifest): MutableMap<String, Any?> =
        linkedMapOf(
            "schemaVersion" to manifest.schemaVersion,
            "id" to manifest.id,
            "version" to manifest.version,
            "minecraft" to
                linkedMapOf(
                    "version" to manifest.minecraft.version,
                    "resourcePackFormat" to manifest.minecraft.resourcePackFormat,
                ),
            "catalog" to
                linkedMapOf(
                    "id" to manifest.catalog.id,
                    "version" to manifest.catalog.version,
                    "coordinate" to manifest.catalog.coordinate,
                    "file" to manifest.catalog.file,
                    "sha256" to manifest.catalog.sha256,
                    "size" to manifest.catalog.size,
                ),
            "packs" to
                manifest.packs.mapTo(mutableListOf()) { pack ->
                    linkedMapOf(
                        "order" to pack.order,
                        "role" to pack.role,
                        "id" to pack.id,
                        "uuid" to pack.uuid.toString(),
                        "required" to pack.required,
                        "url" to pack.url,
                        "sha1" to pack.sha1,
                        "sha256" to pack.sha256,
                        "size" to pack.size,
                        "resourcePackFormat" to pack.resourcePackFormat,
                    )
                },
            "provenance" to
                linkedMapOf(
                    "repository" to manifest.provenance.repository,
                    "commit" to manifest.provenance.commit,
                    "tag" to manifest.provenance.tag,
                ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun deepCopy(value: Any?): MutableMap<String, Any?> =
        copyValue(value) as MutableMap<String, Any?>

    private fun copyValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                value.entries.associateTo(linkedMapOf()) { it.key as String to copyValue(it.value) }
            is List<*> -> value.mapTo(mutableListOf(), ::copyValue)
            else -> value
        }

    @Suppress("UNCHECKED_CAST")
    private fun parent(root: MutableMap<String, Any?>, path: List<Any>): MutableMap<String, Any?> =
        objectAt(root, path.dropLast(1))

    @Suppress("UNCHECKED_CAST")
    private fun objectAt(
        root: MutableMap<String, Any?>,
        path: List<Any>,
    ): MutableMap<String, Any?> {
        var value: Any? = root
        path.forEach { segment ->
            value =
                when (segment) {
                    is String -> (value as Map<String, Any?>).getValue(segment)
                    is Int -> (value as List<Any?>)[segment]
                    else -> error("Unsupported path segment")
                }
        }
        return value as MutableMap<String, Any?>
    }

    private fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            is Boolean,
            is Number -> value.toString()
            is Map<*, *> ->
                value.entries.joinToString(",", "{", "}") { (key, item) ->
                    "${json(key as String)}:${json(item)}"
                }
            is List<*> -> value.joinToString(",", "[", "]", transform = ::json)
            else -> error("Unsupported JSON fixture value: $value")
        }
}

private fun problem(pointer: String, code: ManifestProblemCode, message: String) =
    ManifestProblem(pointer, code, message)
