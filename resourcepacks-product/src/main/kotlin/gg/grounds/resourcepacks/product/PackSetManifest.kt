package gg.grounds.resourcepacks.product

import java.nio.file.Path
import java.util.UUID

internal data class PackSetManifest(
    val version: String,
    val minecraft: MinecraftManifest,
    val catalog: CatalogManifest,
    val packs: List<PackManifest>,
    val provenance: ProvenanceManifest,
    val schemaVersion: Int = 1,
    val id: String = "grounds:global",
)

internal data class MinecraftManifest(val version: String, val resourcePackFormat: Int)

internal data class CatalogManifest(
    val id: String,
    val version: String,
    val coordinate: String,
    val file: String,
    val sha256: String,
    val size: Long,
)

internal data class PackManifest(
    val order: Int,
    val role: String,
    val id: String,
    val uuid: UUID,
    val required: Boolean,
    val url: String,
    val sha1: String,
    val sha256: String,
    val size: Long,
    val resourcePackFormat: Int,
)

internal data class ProvenanceManifest(val repository: String, val commit: String, val tag: String)

/** Task 6's narrow view; Task 7 can own and extend this as its release-artifact model. */
internal data class ManifestArtifacts(val catalog: Path, val packs: Map<PackRole, Path>)

internal data class ManifestProblem(
    val pointer: String,
    val code: ManifestProblemCode,
    val message: String,
)

internal enum class ManifestProblemCode {
    MALFORMED_JSON,
    DUPLICATE_KEY,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    INVALID_VALUE,
    ARTIFACT_MISSING,
    ARTIFACT_NOT_REGULAR,
    ARTIFACT_CHANGED,
    ARTIFACT_MISMATCH,
}

internal data class ManifestValidationResult(
    val manifest: PackSetManifest?,
    val problems: List<ManifestProblem>,
) {
    val isValid: Boolean
        get() = problems.isEmpty()
}
