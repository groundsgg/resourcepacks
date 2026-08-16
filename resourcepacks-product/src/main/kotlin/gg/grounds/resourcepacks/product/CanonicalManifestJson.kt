package gg.grounds.resourcepacks.product

import java.nio.charset.StandardCharsets

/**
 * Product-owned canonical serializer: Jackson is intentionally used only to read untrusted JSON.
 */
internal object CanonicalManifestJson {
    fun write(manifest: PackSetManifest): ByteArray =
        value(root(manifest), 0).plus('\n').toByteArray(StandardCharsets.UTF_8)

    fun write(manifest: gg.grounds.resourcepacks.contract.PackSetManifest): ByteArray =
        value(
                obj(
                    "schemaVersion" to manifest.schemaVersion,
                    "packSet" to manifest.packSet,
                    "publication" to
                        obj(
                            "type" to manifest.publication.type.name.lowercase(),
                            "id" to manifest.publication.id,
                        ),
                    "version" to manifest.version,
                    "minecraft" to
                        obj(
                            "version" to manifest.minecraft.version,
                            "resourcePackFormat" to manifest.minecraft.resourcePackFormat,
                        ),
                    "catalog" to
                        obj(
                            "id" to manifest.catalog.id,
                            "version" to manifest.catalog.version,
                            "coordinate" to manifest.catalog.coordinate,
                            "file" to manifest.catalog.file,
                            "sha256" to manifest.catalog.sha256,
                            "size" to manifest.catalog.size,
                        ),
                    "packs" to
                        manifest.packs.map { pack ->
                            obj(
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
                        obj(
                            "repository" to manifest.provenance.repository,
                            "commit" to manifest.provenance.commit,
                        ),
                ),
                0,
            )
            .plus('\n')
            .toByteArray(StandardCharsets.UTF_8)

    private fun root(m: PackSetManifest) =
        obj(
            "schemaVersion" to m.schemaVersion,
            "id" to m.id,
            "version" to m.version,
            "minecraft" to
                obj(
                    "version" to m.minecraft.version,
                    "resourcePackFormat" to m.minecraft.resourcePackFormat,
                ),
            "catalog" to
                obj(
                    "id" to m.catalog.id,
                    "version" to m.catalog.version,
                    "coordinate" to m.catalog.coordinate,
                    "file" to m.catalog.file,
                    "sha256" to m.catalog.sha256,
                    "size" to m.catalog.size,
                ),
            "packs" to
                m.packs.map { p ->
                    obj(
                        "order" to p.order,
                        "role" to p.role,
                        "id" to p.id,
                        "uuid" to p.uuid.toString(),
                        "required" to p.required,
                        "url" to p.url,
                        "sha1" to p.sha1,
                        "sha256" to p.sha256,
                        "size" to p.size,
                        "resourcePackFormat" to p.resourcePackFormat,
                    )
                },
            "provenance" to
                obj(
                    "repository" to m.provenance.repository,
                    "commit" to m.provenance.commit,
                    "tag" to m.provenance.tag,
                ),
        )

    private fun obj(vararg fields: Pair<String, Any?>): Map<String, Any?> = fields.toMap()

    private fun value(value: Any?, depth: Int): String =
        when (value) {
            is Map<*, *> ->
                value.entries
                    .sortedWith { left, right ->
                        compareCodePoints(left.key as String, right.key as String)
                    }
                    .joinToString(",\n", "{\n", "\n${indent(depth)}}") { (key, item) ->
                        "${indent(depth + 1)}${string(key as String)}: ${value(item, depth + 1)}"
                    }
            is List<*> ->
                if (value.isEmpty()) "[]"
                else
                    value.joinToString(",\n", "[\n", "\n${indent(depth)}]") {
                        "${indent(depth + 1)}${value(it, depth + 1)}"
                    }
            is String -> string(value)
            is Int,
            is Long -> value.toString()
            is Boolean -> value.toString()
            else -> error("Unsupported canonical JSON value: $value")
        }

    private fun indent(depth: Int) = "  ".repeat(depth)

    private fun compareCodePoints(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val comparison = left.codePointAt(leftIndex).compareTo(right.codePointAt(rightIndex))
            if (comparison != 0) return comparison
            leftIndex += Character.charCount(left.codePointAt(leftIndex))
            rightIndex += Character.charCount(right.codePointAt(rightIndex))
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    private fun string(value: String): String {
        require(!hasUnpairedSurrogate(value)) { "Malformed or unpaired UTF-16 surrogate." }
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                if (index + 1 == value.length || !value[index + 1].isLowSurrogate()) return true
                index++
            } else if (char.isLowSurrogate()) return true
            index++
        }
        return false
    }
}
