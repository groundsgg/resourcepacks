package gg.grounds.resourcepacks.client

data class ResolverCache(
    val channelEtag: String?,
    val channelBytes: ByteArray?,
    val manifestEtag: String?,
    val manifestBytes: ByteArray?,
    val snapshot: PackSetSnapshot?,
)

sealed interface RefreshResult {
    data class Activated(val snapshot: PackSetSnapshot, internal val cache: ResolverCache) :
        RefreshResult

    data class Unchanged(val snapshot: PackSetSnapshot, internal val cache: ResolverCache) :
        RefreshResult

    data class Failed(val reason: String) : RefreshResult
}
