package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackBuildException
import gg.grounds.resourcepack.api.PackEntrySource
import gg.grounds.resourcepack.api.PackProblem
import gg.grounds.resourcepack.api.PackProblemCode
import gg.grounds.resourcepack.builder.ResourcePackComposer
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class BuiltReleasePack(
    val pack: PhysicalPack,
    val scratchFile: Path,
    val digests: ArtifactDigests,
)

/** Validates packs and streams deterministic ZIP bytes into descriptor-owned secure scratch. */
internal object ReleasePackComposer {
    fun build(
        packs: List<PhysicalPack>,
        scratch: SecureOwnedDirectory,
        hooks: PackComposerHooks,
    ): List<BuiltReleasePack> {
        val orderedPacks = validatedProductPacks(packs)
        val composer = ResourcePackComposer()
        orderedPacks.forEach { pack ->
            // Validation is authoritative, but compose() would allocate encoded metadata bytes.
            composer.validate(pack.definition, pack.contributions).throwIfInvalid()
        }
        orderedPacks.forEach { pack ->
            val pending = Path.of(".${pack.id}.pending.zip")
            hooks.afterComposeBeforeWrite(pack, scratch.stablePath.resolve(pending))
        }
        return orderedPacks.map { pack ->
            val pending = Path.of(".${pack.id}.pending.zip")
            val artifactLimit = requireNotNull(pack.definition.policy.limits.maxArtifactBytes)
            val snapshot =
                scratch.writeRegularFile(
                    pending,
                    artifactLimit,
                    limitFailure = { artifactSizeLimitFailure(artifactLimit) },
                ) { output ->
                    deterministicZip(pack, output)
                }
            hooks.afterPackPublicationVerified(pack, scratch.stablePath.resolve(pending))
            BuiltReleasePack(pack, pending, snapshot.digests)
        }
    }

    private fun deterministicZip(pack: PhysicalPack, output: OutputStream) {
        val entries =
            buildList {
                    pack.contributions.forEach { contribution ->
                        contribution.entries.forEach { entry ->
                            add(StreamingEntry.Source(entry.path.value, entry.source))
                        }
                    }
                    add(StreamingEntry.Metadata("pack.mcmeta", pack))
                    pack.definition.icon?.let { add(StreamingEntry.Source("pack.png", it)) }
                }
                .sortedBy(StreamingEntry::path)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            entries.forEach { entry ->
                zip.putNextEntry(
                    ZipEntry(entry.path).apply {
                        method = ZipEntry.DEFLATED
                        setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                        time = time
                        comment = null
                        extra = EMPTY_EXTRA
                    }
                )
                when (entry) {
                    is StreamingEntry.Source -> streamSource(entry.source, zip)
                    is StreamingEntry.Metadata -> writePackMetadata(entry.pack, zip)
                }
                zip.closeEntry()
            }
        }
    }

    private fun streamSource(source: PackEntrySource, output: OutputStream) {
        source.openStream().use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
    }

    private fun writePackMetadata(pack: PhysicalPack, output: OutputStream) {
        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        val format = pack.definition.format
        writer.append("{\"pack\":{\"pack_format\":")
        writer.append(format.format.toString())
        writer.append(",\"min_format\":")
        writer.append(format.range.minInclusive.toString())
        writer.append(",\"max_format\":")
        writer.append(format.range.maxInclusive.toString())
        writer.append(",\"description\":\"")
        pack.definition.description.forEach { character ->
            when (character) {
                '"' -> writer.append("\\\"")
                '\\' -> writer.append("\\\\")
                '\b' -> writer.append("\\b")
                '\u000C' -> writer.append("\\f")
                '\n' -> writer.append("\\n")
                '\r' -> writer.append("\\r")
                '\t' -> writer.append("\\t")
                else ->
                    if (character < ' ') {
                        writer.append("\\u")
                        writer.append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        writer.append(character)
                    }
            }
        }
        writer.append("\"}}\n")
        writer.flush()
    }

    private sealed interface StreamingEntry {
        val path: String

        data class Source(override val path: String, val source: PackEntrySource) : StreamingEntry

        data class Metadata(override val path: String, val pack: PhysicalPack) : StreamingEntry
    }

    private val EMPTY_EXTRA = ByteArray(0)
    private const val STREAM_BUFFER_SIZE = 64 * 1024
}

private fun artifactSizeLimitFailure(limit: Long): PackBuildException =
    PackBuildException(
        listOf(
            PackProblem(
                PackProblemCode.SIZE_LIMIT_EXCEEDED,
                "ZIP artifact size exceeds the configured limit of $limit bytes.",
            )
        )
    )
