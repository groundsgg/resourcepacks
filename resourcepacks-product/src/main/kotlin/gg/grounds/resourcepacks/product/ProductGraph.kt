package gg.grounds.resourcepacks.product

import gg.grounds.gui.pack.toPackContribution
import gg.grounds.resourcepack.api.ByteArrayEntrySource
import gg.grounds.resourcepack.api.FileEntrySource
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackDefinition
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackEntrySource
import gg.grounds.resourcepack.api.PackPolicy
import gg.grounds.resourcepack.api.VanillaPathPolicy
import gg.grounds.resourcepacks.catalog.GroundsGuiTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal enum class PackRole {
    CONTENT,
    PLATFORM,
}

internal data class PhysicalPack(
    val order: Int,
    val role: PackRole,
    val id: String,
    val uuid: UUID,
    val required: Boolean,
    val definition: PackDefinition,
    val contributions: List<PackContribution>,
)

internal object ProductGraph {
    val packs: List<PhysicalPack>
        get() = createPacks()

    /**
     * Opens every product artwork component before library-gui materialization, pins the exact
     * approved bytes, and keeps those descriptors alive until PackSet commit.
     */
    fun secureReleaseInputs(): SecureSourceInputs = secureReleaseInputs(platformArtDirectory()) {}

    @JvmSynthetic
    internal fun secureReleaseInputs(
        art: Path,
        afterSourcesPinned: () -> Unit,
    ): SecureSourceInputs {
        val held = linkedMapOf<String, HeldSourceFile>()
        try {
            PLATFORM_ART_SHA256.forEach { (relative, expectedSha256) ->
                val source = HeldSourceFile.capture(art.resolve(relative))
                if (source.digests.sha256 != expectedSha256) {
                    source.close()
                    throw java.io.IOException("Product artwork hash mismatch: $relative")
                }
                held[relative] = source
            }
            afterSourcesPinned()
            val contribution = immutableThemeContribution(held)
            return SecureSourceInputs.captureWithHeld(
                    createPacks(contribution),
                    held.values.toList(),
                )
                .also { it.verifyUnchanged() }
        } catch (failure: Throwable) {
            held.values.toList().asReversed().forEach { source ->
                try {
                    source.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
            }
            throw failure
        }
    }

    private fun createPacks(): List<PhysicalPack> =
        createPacks(GroundsGuiTheme.theme.toPackContribution(platformArtDirectory()))

    private fun createPacks(platformContribution: PackContribution): List<PhysicalPack> =
        listOf(
            PhysicalPack(
                order = 0,
                role = PackRole.CONTENT,
                id = "grounds-content",
                uuid = PackSetConstants.contentUuid,
                required = true,
                definition =
                    PackDefinition(
                        "Grounds content",
                        PackSetConstants.packFormat,
                        null,
                        PackPolicy(VanillaPathPolicy.FORBID, PackSetConstants.contentLimits),
                    ),
                contributions = listOf(ContentContribution),
            ),
            PhysicalPack(
                order = 1,
                role = PackRole.PLATFORM,
                id = "grounds-platform",
                uuid = PackSetConstants.platformUuid,
                required = true,
                definition =
                    PackDefinition(
                        "Grounds platform",
                        PackSetConstants.packFormat,
                        ByteArrayEntrySource(ByteArray(0)),
                        PackPolicy(VanillaPathPolicy.ALLOW_CLAIMED, PackSetConstants.platformLimits),
                    ),
                contributions = listOf(platformContribution),
            ),
        )

    /** Materializes library-gui from immutable bytes, never from mutable source paths. */
    private fun immutableThemeContribution(sources: Map<String, HeldSourceFile>): PackContribution {
        val immutableBytes = sources.mapValues { (_, source) -> source.bytes }
        return ImmutableSourceFileSystem.open(immutableBytes).use { fileSystem ->
            GroundsGuiTheme.theme.toPackContribution(fileSystem.root).withImmutableSources()
        }
    }

    private fun PackContribution.withImmutableSources(): PackContribution =
        PackContribution(
            id,
            supportedFormats,
            entries.map { entry -> PackEntry(entry.path, entry.source.immutableBytes()) },
            vanillaClaims,
            provides,
            requires,
        )

    private fun PackEntrySource.immutableBytes(): PackEntrySource =
        when (this) {
            is FileEntrySource -> ByteArrayEntrySource(openStream().use { it.readAllBytes() })
            is ByteArrayEntrySource -> ByteArrayEntrySource(openStream().use { it.readAllBytes() })
        }

    private fun platformArtDirectory(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("art/platform")

    private val PLATFORM_ART_SHA256 =
        linkedMapOf(
            "frames/hover.png" to
                "73460b5b00c8d5f984dd274c8852c44dd05f8819679691f92e624f8ada3e9a31",
            "icons/back.png" to "651f99c806c5d57f204d4505a9452d0a9e51f1e63ea85bc8fb22be6ea6805441",
            "icons/blank.png" to "a901afae7bdb66678f08a39b32f8a46da9864c8a64fabc0e77a7f12b93df12ba",
            "icons/close.png" to "7765185bdc454529602d98c0e9551c97063e6ee09ed4537fa4845103d2015313",
            "icons/next.png" to "dfa66166eefc04906f4d2d07d93a9b18249143def5d0b156c1343d1e5b151738",
            "panels/menu.png" to "9e9e6760d0ce3562000b11c6a6e9eb84d20bdd59f9d7dc9c5a72dc51c856fd64",
            "tooltips/default_bg.png" to
                "364059a08c651259e009cdd0aeb98f36379f6db113917a23674469ff27de3f48",
            "tooltips/default_frame.png" to
                "a1c5355ab5945428d821a62a0eab7948f04443c7622e1fbd96ea67776e6da966",
        )
}
