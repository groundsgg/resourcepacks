package gg.grounds.resourcepacks.product

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SidebarPackTest {
    @Test
    fun `platform ships sidebar glyphs alongside exactly one existing shader per path`() {
        ProductGraph.secureReleaseInputs().use { secured ->
            val parent = Files.createTempDirectory("sidebar-pack-")
            val scratch = SecureOwnedDirectory.create(parent, ".sidebar-pack-")
            try {
                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val platform = built.single { it.pack.role == PackRole.PLATFORM }
                ZipFile(scratch.stablePath.resolve(platform.scratchFile).toFile()).use { zip ->
                    listOf(
                            "assets/grounds/font/sidebar.json",
                            "assets/grounds/font/game_sidebar.json",
                            "assets/grounds/textures/font/game_sidebar_bed.png",
                            "assets/grounds/textures/font/game_sidebar_trophy.png",
                            "assets/grounds/font/sidebar_footer.json",
                            "assets/grounds/textures/font/sidebar_logo.png",
                            "assets/grounds/textures/font/sidebar_bar.png",
                        )
                        .forEach { assertNotNull(zip.getEntry(it), it) }
                    listOf("gui.vsh", "gui.fsh", "text.vsh").forEach { file ->
                        assertEquals(
                            1,
                            zip.entries().asSequence().count {
                                it.name == "assets/minecraft/shaders/core/$file"
                            },
                        )
                    }
                }
            } finally {
                scratch.close()
                parent.toFile().deleteRecursively()
            }
        }
    }
}
