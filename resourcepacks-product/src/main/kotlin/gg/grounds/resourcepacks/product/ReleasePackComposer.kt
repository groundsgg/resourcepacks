package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ByteArrayEntrySource
import gg.grounds.resourcepack.api.PackBuildException
import gg.grounds.resourcepack.api.PackProblem
import gg.grounds.resourcepack.api.PackProblemCode
import gg.grounds.resourcepack.builder.ResourcePackComposer
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class BuiltPhysicalPackBytes(
    val pack: PhysicalPack,
    bytes: ByteArray,
    val digests: ArtifactDigests,
) {
    private val storedBytes = bytes.copyOf()
    val bytes: ByteArray
        get() = storedBytes.copyOf()
}

/** Validates and creates deterministic ZIP bytes without creating, renaming, or deleting paths. */
internal object ReleasePackComposer {
    fun build(packs: List<PhysicalPack>, hooks: PackComposerHooks): List<BuiltPhysicalPackBytes> {
        val orderedPacks = validatedProductPacks(packs)
        val composer = ResourcePackComposer()
        return orderedPacks.map { pack ->
            // The composer remains the authoritative validation boundary. Release inputs have
            // already been converted to immutable byte-backed sources.
            composer.compose(pack.definition, pack.contributions)
            val diagnosticTarget = Path.of(".${pack.id}.pending.zip")
            hooks.afterComposeBeforeWrite(pack, diagnosticTarget)
            val bytes = deterministicZipBytes(pack)
            val digests = ArtifactDigests.fromBytes(bytes)
            val limit = pack.definition.policy.limits.maxArtifactBytes
            if (limit != null && digests.size > limit) {
                throw PackBuildException(
                    listOf(
                        PackProblem(
                            PackProblemCode.SIZE_LIMIT_EXCEEDED,
                            "ZIP artifact size exceeds the configured limit of $limit bytes.",
                        )
                    )
                )
            }
            hooks.afterPackPublicationVerified(pack, diagnosticTarget)
            BuiltPhysicalPackBytes(pack, bytes, digests)
        }
    }

    private fun deterministicZipBytes(pack: PhysicalPack): ByteArray {
        val entries =
            buildList {
                    pack.contributions.forEach { contribution ->
                        contribution.entries.forEach { entry ->
                            add(entry.path.value to entry.source)
                        }
                    }
                    add("pack.mcmeta" to ByteArrayEntrySource(packMetadata(pack)))
                    pack.definition.icon?.let { add("pack.png" to it) }
                }
                .sortedBy { it.first }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            entries.forEach { (path, source) ->
                zip.putNextEntry(
                    ZipEntry(path).apply {
                        method = ZipEntry.DEFLATED
                        setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                        time = time
                        comment = null
                        extra = ByteArray(0)
                    }
                )
                source.openStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun packMetadata(pack: PhysicalPack): ByteArray {
        val definition = pack.definition
        val format = definition.format
        return buildString {
                append("{\"pack\":{\"pack_format\":")
                append(format.format)
                append(",\"min_format\":")
                append(format.range.minInclusive)
                append(",\"max_format\":")
                append(format.range.maxInclusive)
                append(",\"description\":\"")
                definition.description.forEach { character ->
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else ->
                            if (character < ' ') {
                                append("\\u")
                                append(character.code.toString(16).padStart(4, '0'))
                            } else {
                                append(character)
                            }
                    }
                }
                append("\"}}\n")
            }
            .encodeToByteArray()
    }
}
