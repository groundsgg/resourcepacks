package gg.grounds.resourcepacks.product

import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO
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
                    // Golden pixels from variant A, approved in the native Minecraft 26.3
                    // comparison at GUI scales 2 and 3. Includes the original alpha padding.
                    val logo =
                        zip.getInputStream(
                                zip.getEntry("assets/grounds/textures/font/sidebar_logo.png")
                            )
                            .use { ImageIO.read(it) }
                    assertEquals(216, logo.width)
                    assertEquals(78, logo.height)
                    val digest = MessageDigest.getInstance("SHA-256")
                    repeat(logo.height) { y ->
                        repeat(logo.width) { x ->
                            val pixel = logo.getRGB(x, y)
                            for (shift in listOf(24, 16, 8, 0)) digest.update(
                                (pixel ushr shift).toByte()
                            )
                        }
                    }
                    assertEquals(
                        "9ac47fa18f90f967c8cd194894d9d22c4ec0300193d9a70312f7963809bf2787",
                        digest.digest().joinToString("") { "%02x".format(it) },
                        "The built pack must contain the native-tested, approved logo raster",
                    )
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
