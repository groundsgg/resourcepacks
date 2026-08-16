package gg.grounds.resourcepacks.product

data class ArtifactLocation(val fileName: String, val objectKey: String, val publicUrl: String)

object PackSetObjectLayout {
    const val PACK_SET_ID: String = "grounds-global"

    fun content(identity: PublicationIdentity, sha1: String): ArtifactLocation {
        requireSha1(sha1)
        return location(identity, "grounds-content-pack-${fileSuffix(identity)}.zip")
    }

    fun platform(identity: PublicationIdentity, sha1: String): ArtifactLocation {
        requireSha1(sha1)
        return location(identity, "grounds-platform-pack-${fileSuffix(identity)}.zip")
    }

    fun catalog(identity: PublicationIdentity): ArtifactLocation =
        location(identity, "grounds-resourcepack-catalog-${fileSuffix(identity)}.jar")

    fun manifest(identity: PublicationIdentity): ArtifactLocation =
        location(identity, "manifest.json")

    internal fun locationFor(
        packSetId: String,
        identity: PublicationIdentity,
        fileName: String,
    ): ArtifactLocation {
        require(packSetId == PACK_SET_ID) { "Unsupported PackSet ID." }
        require(fileName.matches(Regex("[A-Za-z0-9.-]+"))) { "Artifact filename is unsafe." }
        validateIdentity(identity)
        val objectKey =
            "resourcepacks/packsets/$packSetId/${publicationDirectory(identity)}/$fileName"
        return ArtifactLocation(fileName, objectKey, "https://cdn.grounds.gg/$objectKey")
    }

    private fun location(identity: PublicationIdentity, fileName: String): ArtifactLocation =
        locationFor(PACK_SET_ID, identity, fileName)

    private fun fileSuffix(identity: PublicationIdentity): String {
        validateIdentity(identity)
        return when (identity.type) {
            PublicationType.RELEASE -> identity.id
            PublicationType.BUILD -> "edge-${identity.commit.take(12)}"
        }
    }

    private fun publicationDirectory(identity: PublicationIdentity): String =
        when (identity.type) {
            PublicationType.RELEASE -> "releases/${identity.id}"
            PublicationType.BUILD -> "builds/${identity.commit}"
        }

    private fun validateIdentity(identity: PublicationIdentity) {
        require(LOWERCASE_SHA1.matches(identity.commit)) { "Commit must be lowercase 40-hex." }
        when (identity.type) {
            PublicationType.RELEASE -> {
                require(SEMVER.matches(identity.version)) { "Release version must be SemVer." }
                require(identity.id == "v${identity.version}") {
                    "Release ID must equal v<version>."
                }
            }
            PublicationType.BUILD -> {
                require(identity.id == identity.commit) { "Build ID must equal commit." }
                val match =
                    EDGE_VERSION.matchEntire(identity.version)
                        ?: throw IllegalArgumentException(
                            "Build version must be a valid Edge version."
                        )
                require(match.groupValues[1] == identity.commit.take(12)) {
                    "Build version short commit must match commit."
                }
            }
        }
    }

    private fun requireSha1(sha1: String) {
        require(LOWERCASE_SHA1.matches(sha1)) { "SHA-1 must be lowercase 40-hex." }
    }

    private val LOWERCASE_SHA1 = Regex("[0-9a-f]{40}")
    private val SEMVER =
        Regex(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)" +
                "(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
        )
    private val EDGE_VERSION = Regex("0\\.0\\.0-edge\\.[1-9][0-9]*\\.g([0-9a-f]{12})")
}
