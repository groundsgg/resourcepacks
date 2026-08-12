package gg.grounds.resourcepacks.product

import java.nio.file.Path

internal data class ReleaseArtifact(
    val file: Path,
    val sha1: String,
    val sha256: String,
    val size: Long,
)

/** Immutable description of the four files atomically published by [PackSetBuilder]. */
internal class ReleaseArtifacts(
    val content: ReleaseArtifact,
    val platform: ReleaseArtifact,
    val catalog: ReleaseArtifact,
    val manifest: ReleaseArtifact,
)
