package gg.grounds.resourcepacks.catalog

import gg.grounds.gui.pack.toPackContribution
import gg.grounds.resourcepack.api.RenderingCapability
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeAssetContractTest {
    private val platformArt: Path = projectRoot().resolve("art/platform")

    @Test
    fun `theme art has the required dimensions alpha and native icon palette`() {
        assertImage("panels/menu.png", 176, 168, hasOpaquePixel = true)
        assertImage("icons/close.png", 16, 16, hasOpaquePixel = true)
        assertImage("icons/back.png", 16, 16, hasOpaquePixel = true)
        assertImage("icons/next.png", 16, 16, hasOpaquePixel = true)
        assertImage("icons/blank.png", 16, 16, hasOpaquePixel = false)
        assertImage("tooltips/default_bg.png", 24, 24, hasOpaquePixel = true)
        assertImage("tooltips/default_frame.png", 24, 24, hasOpaquePixel = true)
        assertImage("frames/hover.png", 56, 56, hasOpaquePixel = true)

        listOf("close", "back", "next").forEach { icon ->
            val image = readImage("icons/$icon.png")
            val palette = image.pixels().filter { it ushr 24 != 0 }.toSet()
            assertTrue(palette.size in 2..3, "$icon palette: $palette")
            assertTrue(palette.all { it in NATIVE_ICON_PALETTE }, "$icon palette: $palette")
        }
        assertTrue(readImage("icons/blank.png").pixels().all { it == 0 }, "blank icon must be fully transparent")
    }

    @Test
    fun `nonblank art has no hidden transparent RGB payload`() {
        ART_FILES.filterNot { it == "icons/blank.png" }
            .forEach { path ->
                readImage(path).pixels().forEach { pixel ->
                    assertTrue(
                        pixel ushr 24 != 0 || pixel == 0,
                        "$path has hidden RGB in a transparent pixel",
                    )
                }
            }
    }

    @Test
    fun `approved example files match their pinned origins when checkout is available`() {
        val manifest = Files.readString(platformArt.resolve("ASSET_ORIGINS.json"))
        assertEquals(EXPECTED_ORIGINS, manifest.trim())

        APPROVED_ORIGINS.forEach { (destination, origin) ->
            val destinationFile = platformArt.resolve(destination)
            assertEquals(origin.sha256, sha256(destinationFile), destination)
            exampleArtRootOrNull()?.resolve(origin.source)?.let { sourceFile ->
                assertTrue(Files.isRegularFile(sourceFile), "Missing approved source $sourceFile")
                assertContentEquals(
                    Files.readAllBytes(sourceFile),
                    Files.readAllBytes(destinationFile),
                    destination,
                )
            }
        }
    }

    @Test
    fun `theme materializes with precisely its hover shader capability`() {
        val contribution = GroundsGuiTheme.theme.toPackContribution(platformArt)

        assertEquals(
            setOf(RenderingCapability("grounds:text-marker-shader", 1)),
            contribution.provides,
        )
    }

    private fun assertImage(path: String, width: Int, height: Int, hasOpaquePixel: Boolean) {
        val image = readImage(path)
        assertEquals(width, image.width, path)
        assertEquals(height, image.height, path)
        assertEquals(hasOpaquePixel, image.pixels().any { it ushr 24 != 0 }, path)
    }

    private fun readImage(path: String): BufferedImage =
        requireNotNull(ImageIO.read(platformArt.resolve(path).toFile())) {
            "Unreadable image: $path"
        }

    private fun BufferedImage.pixels(): Sequence<Int> = sequence {
        for (y in 0 until height) for (x in 0 until width) yield(getRGB(x, y))
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
            "%02x".format(it)
        }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun exampleArtRootOrNull(): Path? {
        val checkout = Path.of("/home/lukas/grounds/library-gui/examples/theme-demo/art")
        return checkout.takeIf(Files::isDirectory)
    }

    private data class Origin(val source: String, val sha256: String)

    private companion object {
        val NATIVE_ICON_PALETTE = setOf(0xff202020.toInt(), 0xffc8c8c8.toInt(), 0xff7a7a7a.toInt())
        val ART_FILES =
            listOf(
                "panels/menu.png",
                "icons/close.png",
                "icons/back.png",
                "icons/next.png",
                "icons/blank.png",
                "tooltips/default_bg.png",
                "tooltips/default_frame.png",
                "frames/hover.png",
            )
        val APPROVED_ORIGINS =
            mapOf(
                "panels/menu.png" to
                    Origin(
                        "panels/shop.png",
                        "9e9e6760d0ce3562000b11c6a6e9eb84d20bdd59f9d7dc9c5a72dc51c856fd64",
                    ),
                "icons/blank.png" to
                    Origin(
                        "icons/blank.png",
                        "a901afae7bdb66678f08a39b32f8a46da9864c8a64fabc0e77a7f12b93df12ba",
                    ),
                "tooltips/default_bg.png" to
                    Origin(
                        "tooltips/steel_bg.png",
                        "364059a08c651259e009cdd0aeb98f36379f6db113917a23674469ff27de3f48",
                    ),
                "tooltips/default_frame.png" to
                    Origin(
                        "tooltips/steel_frame.png",
                        "a1c5355ab5945428d821a62a0eab7948f04443c7622e1fbd96ea67776e6da966",
                    ),
                "frames/hover.png" to
                    Origin(
                        "frame/square.png",
                        "73460b5b00c8d5f984dd274c8852c44dd05f8819679691f92e624f8ada3e9a31",
                    ),
            )
        val EXPECTED_ORIGINS =
            """
{
  "approvedExampleCopies": {
    "frames/hover.png": {
      "source": "library-gui/examples/theme-demo/art/frame/square.png",
      "sha256": "73460b5b00c8d5f984dd274c8852c44dd05f8819679691f92e624f8ada3e9a31"
    },
    "icons/blank.png": {
      "source": "library-gui/examples/theme-demo/art/icons/blank.png",
      "sha256": "a901afae7bdb66678f08a39b32f8a46da9864c8a64fabc0e77a7f12b93df12ba"
    },
    "panels/menu.png": {
      "source": "library-gui/examples/theme-demo/art/panels/shop.png",
      "sha256": "9e9e6760d0ce3562000b11c6a6e9eb84d20bdd59f9d7dc9c5a72dc51c856fd64"
    },
    "tooltips/default_bg.png": {
      "source": "library-gui/examples/theme-demo/art/tooltips/steel_bg.png",
      "sha256": "364059a08c651259e009cdd0aeb98f36379f6db113917a23674469ff27de3f48"
    },
    "tooltips/default_frame.png": {
      "source": "library-gui/examples/theme-demo/art/tooltips/steel_frame.png",
      "sha256": "a1c5355ab5945428d821a62a0eab7948f04443c7622e1fbd96ea67776e6da966"
    }
  }
}
"""
                .trim()
    }
}
