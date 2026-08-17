package gg.grounds.resourcepacks.client

internal data class ResolverCache(
    val channelEtag: String?,
    val channelBytes: ByteArray?,
    val manifestEtag: String?,
    val manifestBytes: ByteArray?,
    val snapshot: PackSetSnapshot?,
)

internal sealed interface RefreshResult {
    data class Activated(val snapshot: PackSetSnapshot, val cache: ResolverCache) : RefreshResult

    data class Unchanged(val snapshot: PackSetSnapshot, val cache: ResolverCache) : RefreshResult

    data class Failed(val reason: String) : RefreshResult
}
