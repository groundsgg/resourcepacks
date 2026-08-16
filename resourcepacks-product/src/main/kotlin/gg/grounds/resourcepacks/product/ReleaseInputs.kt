package gg.grounds.resourcepacks.product

import java.nio.file.Path

/** Explicit, reproducible inputs for a single PackSet release build. */
internal data class ReleaseInputs(val publication: PublicationIdentity, val outputDirectory: Path) {
    constructor(
        version: String,
        provenanceCommit: String,
        provenanceTag: String,
        outputDirectory: Path,
    ) : this(
        PublicationIdentity(PublicationType.RELEASE, provenanceTag, version, provenanceCommit),
        outputDirectory,
    )

    val version: String
        get() = publication.version

    val provenanceCommit: String
        get() = publication.commit

    val provenanceTag: String
        get() = publication.id
}
