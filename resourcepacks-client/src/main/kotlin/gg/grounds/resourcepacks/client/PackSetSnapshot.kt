package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.PackSetManifest
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.ConsistentCopyVisibility

data class ResolvedPack(
    val order: Int,
    val role: String,
    val id: String,
    val uuid: UUID,
    val uri: URI,
    val sha1: String,
    val sha256: String,
    val size: Long,
    val required: Boolean,
)

@ConsistentCopyVisibility
data class PackSetSnapshot
private constructor(
    val source: PackSetSource,
    val channel: ChannelDocument,
    val manifest: PackSetManifest,
    val packs: List<ResolvedPack>,
    val fingerprint: String,
    private val immutable: Boolean,
) {
    constructor(
        source: PackSetSource,
        channel: ChannelDocument,
        manifest: PackSetManifest,
        packs: List<ResolvedPack>,
    ) : this(
        source,
        channel,
        manifest,
        Collections.unmodifiableList(ArrayList(packs)),
        semanticFingerprint(channel, manifest),
        true,
    )

    init {
        require(immutable) { "Packs must be an immutable snapshot." }
    }

    internal companion object {
        fun fromValidatedBytes(
            source: PackSetSource,
            channel: ChannelDocument,
            manifest: PackSetManifest,
            packs: List<ResolvedPack>,
            channelBytes: ByteArray,
            manifestBytes: ByteArray,
        ): PackSetSnapshot =
            PackSetSnapshot(
                source,
                channel,
                manifest,
                Collections.unmodifiableList(ArrayList(packs)),
                fingerprint(channelBytes + manifestBytes),
                true,
            )

        private fun fingerprint(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

        private fun semanticFingerprint(
            channel: ChannelDocument,
            manifest: PackSetManifest,
        ): String =
            fingerprint(
                (listOf(
                        channel.schemaVersion.toString(),
                        channel.packSet,
                        channel.channel.name,
                        channel.sequence.toString(),
                        channel.target.type.name,
                        channel.target.id,
                        channel.manifest.url,
                        channel.manifest.sha256,
                        channel.manifest.size.toString(),
                        manifest.schemaVersion.toString(),
                        manifest.packSet,
                        manifest.publication.type.name,
                        manifest.publication.id,
                        manifest.version,
                        manifest.minecraft.version,
                        manifest.minecraft.resourcePackFormat.toString(),
                        manifest.catalog.id,
                        manifest.catalog.version,
                        manifest.catalog.coordinate,
                        manifest.catalog.file,
                        manifest.catalog.sha256,
                        manifest.catalog.size.toString(),
                        manifest.provenance.repository,
                        manifest.provenance.commit,
                    ) +
                        manifest.packs.flatMap { pack ->
                            listOf(
                                pack.order.toString(),
                                pack.role,
                                pack.id,
                                pack.uuid.toString(),
                                pack.required.toString(),
                                pack.url,
                                pack.sha1,
                                pack.sha256,
                                pack.size.toString(),
                                pack.resourcePackFormat.toString(),
                            )
                        })
                    .joinToString("\u0000")
                    .encodeToByteArray()
            )
    }
}
