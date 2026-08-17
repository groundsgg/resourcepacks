package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.PackSetManifest
import java.net.URI
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
        fingerprint: String,
    ) : this(
        source,
        channel,
        manifest,
        Collections.unmodifiableList(ArrayList(packs)),
        fingerprint,
        true,
    )

    init {
        require(immutable) { "Packs must be an immutable snapshot." }
    }
}
