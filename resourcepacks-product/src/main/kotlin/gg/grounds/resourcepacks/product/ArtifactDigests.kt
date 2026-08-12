package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.builder.PackArtifact

/** Final-byte digests reported by [gg.grounds.resourcepack.builder.ZipPackWriter]. */
internal data class ArtifactDigests(val sha1: String, val sha256: String, val size: Long) {
    companion object {
        fun from(artifact: PackArtifact): ArtifactDigests =
            ArtifactDigests(artifact.sha1, artifact.sha256, artifact.size)
    }
}
