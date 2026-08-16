package gg.grounds.resourcepacks.product

enum class PublicationType {
    RELEASE,
    BUILD,
}

data class PublicationIdentity(
    val type: PublicationType,
    val id: String,
    val version: String,
    val commit: String,
)
