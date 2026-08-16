package gg.grounds.resourcepacks.contract

import java.util.Collections
import java.util.UUID

/** The immutable schema-v2 manifest boundary consumed by runtime applications. */
class PackSetManifest(
    val schemaVersion: Int,
    val packSet: String,
    val publication: ManifestPublication,
    val version: String,
    val minecraft: ManifestMinecraft,
    val catalog: ManifestCatalog,
    packs: List<ManifestPack>,
    val provenance: ManifestProvenance,
) {
    val packs: List<ManifestPack> = Collections.unmodifiableList(ArrayList(packs))

    override fun equals(other: Any?): Boolean =
        other is PackSetManifest &&
            schemaVersion == other.schemaVersion &&
            packSet == other.packSet &&
            publication == other.publication &&
            version == other.version &&
            minecraft == other.minecraft &&
            catalog == other.catalog &&
            packs == other.packs &&
            provenance == other.provenance

    override fun hashCode(): Int =
        listOf(schemaVersion, packSet, publication, version, minecraft, catalog, packs, provenance)
            .hashCode()
}

enum class PublicationType {
    RELEASE,
    BUILD,
}

data class ManifestPublication(val type: PublicationType, val id: String)

data class ManifestMinecraft(val version: String, val resourcePackFormat: Int)

data class ManifestCatalog(
    val id: String,
    val version: String,
    val coordinate: String,
    val file: String,
    val sha256: String,
    val size: Long,
)

data class ManifestPack(
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

data class ManifestProvenance(val repository: String, val commit: String)

enum class ManifestDiagnosticCode {
    MALFORMED_JSON,
    NON_CANONICAL_JSON,
    DUPLICATE_KEY,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    INVALID_VALUE,
}

data class ManifestDiagnostic(
    val pointer: String,
    val code: ManifestDiagnosticCode,
    val message: String,
)

sealed interface ManifestDecodeResult {
    data class Success(val manifest: PackSetManifest) : ManifestDecodeResult

    class Failure(diagnostics: List<ManifestDiagnostic>) : ManifestDecodeResult {
        val diagnostics: List<ManifestDiagnostic> =
            Collections.unmodifiableList(ArrayList(diagnostics))

        override fun equals(other: Any?): Boolean =
            other is Failure && diagnostics == other.diagnostics

        override fun hashCode(): Int = diagnostics.hashCode()
    }
}

object ManifestParserLimits {
    const val MAX_DEPTH: Int = 64
    const val MAX_STRING: Int = 16_384
    const val MAX_NUMBER: Int = 128
    const val MAX_DOCUMENT: Int = 1_048_576
}
