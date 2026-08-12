package gg.grounds.resourcepacks.product

import gg.grounds.gui.pack.toPackContribution
import gg.grounds.resourcepack.api.ByteArrayEntrySource
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackDefinition
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
    val packs: List<PhysicalPack> =
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
                contributions =
                    listOf(GroundsGuiTheme.theme.toPackContribution(platformArtDirectory())),
            ),
        )

    private fun platformArtDirectory(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("art/platform")
}
