package gg.grounds.resourcepacks.product

import java.nio.file.Path

/** Explicit, reproducible inputs for a single PackSet release build. */
internal data class ReleaseInputs(
    val version: String,
    val provenanceCommit: String,
    val provenanceTag: String,
    val outputDirectory: Path,
    val catalogJar: Path = Path.of(""),
)
