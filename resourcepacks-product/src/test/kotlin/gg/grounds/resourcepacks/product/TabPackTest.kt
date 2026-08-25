package gg.grounds.resourcepacks.product

import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TabPackTest {
    @Test
    fun `platform zip contains the tab font and four textures`() {
        ProductGraph.secureReleaseInputs().use { secured ->
            val parent = java.nio.file.Files.createTempDirectory("tab-pack-")
            val scratch = SecureOwnedDirectory.create(parent, ".tab-pack-")
            try {
                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val zipPath =
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.PLATFORM }.scratchFile
                    )
                ZipFile(zipPath.toFile()).use { zip ->
                    TAB_PATHS.forEach { path ->
                        assertNotNull(zip.getEntry(path), path)
                    }
                    val font = zip.getInputStream(zip.getEntry("assets/grounds/font/tab.json")).reader().readText()
                    assertTrue("\"file\":\"grounds:font/tab_logo.png\"" in font, font)
                    assertTrue("\\uE000" in font || "\uE000" in font, font)
                }
            } finally {
                scratch.close()
                parent.toFile().deleteRecursively()
            }
        }
    }

    private companion object {
        val TAB_PATHS =
            listOf(
                "assets/grounds/font/tab.json",
                "assets/grounds/textures/font/tab_logo.png",
                "assets/grounds/textures/font/tab_badge_left.png",
                "assets/grounds/textures/font/tab_badge_middle.png",
                "assets/grounds/textures/font/tab_badge_right.png",
            )
    }
}
