package gg.grounds.resourcepacks.product

import java.nio.file.Path
import java.util.Collections
import java.util.UUID

internal class PackSetManifest(
    val version: String,
    val minecraft: MinecraftManifest,
    val catalog: CatalogManifest,
    packs: List<PackManifest>,
    val provenance: ProvenanceManifest,
    val schemaVersion: Int = 1,
    val id: String = "grounds:global",
) {
    val packs: List<PackManifest> = Collections.unmodifiableList(ArrayList(packs))

    fun copy(
        version: String = this.version,
        minecraft: MinecraftManifest = this.minecraft,
        catalog: CatalogManifest = this.catalog,
        packs: List<PackManifest> = this.packs,
        provenance: ProvenanceManifest = this.provenance,
        schemaVersion: Int = this.schemaVersion,
        id: String = this.id,
    ) = PackSetManifest(version, minecraft, catalog, packs, provenance, schemaVersion, id)

    override fun equals(other: Any?) =
        other is PackSetManifest &&
            version == other.version &&
            minecraft == other.minecraft &&
            catalog == other.catalog &&
            packs == other.packs &&
            provenance == other.provenance &&
            schemaVersion == other.schemaVersion &&
            id == other.id

    override fun hashCode() =
        listOf(version, minecraft, catalog, packs, provenance, schemaVersion, id).hashCode()
}

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
internal class ManifestArtifacts(val catalog: Path, packs: Map<PackRole, Path>) {
    val packs: Map<PackRole, Path> = Collections.unmodifiableMap(LinkedHashMap(packs))

    fun copy(catalog: Path = this.catalog, packs: Map<PackRole, Path> = this.packs) =
        ManifestArtifacts(catalog, packs)
}

internal data class ManifestProblem(
    val pointer: String,
    val code: ManifestProblemCode,
    val message: String,
)

internal enum class ManifestProblemCode {
    MALFORMED_JSON,
    NON_CANONICAL_JSON,
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

internal class ManifestValidationResult(
    manifest: PackSetManifest?,
    problems: List<ManifestProblem>,
) {
    val manifest: PackSetManifest? = manifest
    val problems: List<ManifestProblem> = Collections.unmodifiableList(ArrayList(problems))
    val isValid: Boolean
        get() = problems.isEmpty()
}
