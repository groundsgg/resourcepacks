package gg.grounds.resourcepacks.client

enum class PackSetClientStatus {
    STARTING,
    READY,
    DEGRADED,
    UNAVAILABLE,
    CLOSED,
}

data class PackSetClientState(
    val source: PackSetSource,
    val current: PackSetSnapshot?,
    val degradedFallback: PackSetSnapshot?,
    val status: PackSetClientStatus,
    val lastError: String?,
)
