package gg.grounds.resourcepacks.catalog

import gg.grounds.gui.pack.toPackContribution
import gg.grounds.resourcepack.api.RenderingCapability
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ThemeAssetContractTest {
    private val platformArt: Path = projectRoot().resolve("art/platform")

    @Test
    fun `theme art has the required dimensions alpha and native icon palette`() {
        validatePlatformArt(platformArt)
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
        assertTrue(
            readImage("icons/blank.png").pixels().all { it == 0 },
            "blank icon must be fully transparent",
        )
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
    fun `platform art rejects an unlisted regular file`() {
        val copiedArt = copyPlatformArt()
        try {
            Files.writeString(copiedArt.resolve("unlisted.txt"), "not an asset")

            assertFails { validatePlatformArt(copiedArt) }
        } finally {
            deleteTree(copiedArt)
        }
    }

    @Test
    fun `platform art rejects unexpected directories`() {
        val copiedArt = copyPlatformArt()
        try {
            Files.createDirectories(copiedArt.resolve("unlisted/nested"))

            assertFails { validatePlatformArt(copiedArt) }
        } finally {
            deleteTree(copiedArt)
        }
    }

    @Test
    fun `platform art rejects symbolic links without following them`() {
        val copiedArt = copyPlatformArt()
        try {
            Files.createSymbolicLink(
                copiedArt.resolve("linked.png"),
                copiedArt.resolve("icons/blank.png"),
            )

            assertFails { validatePlatformArt(copiedArt) }
        } finally {
            deleteTree(copiedArt)
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
        exampleArtRootOrNull()?.let(::assertNoUnlistedExampleCopy)
    }

    @Test
    fun `tab logo fits the 256 px font atlas`() {
        val image = readImage("tab/logo.png")
        assertTrue(image.width in 1..256, "width=${image.width}")
        assertTrue(image.height in 1..256, "height=${image.height}")
        assertTrue(image.width > image.height, "wordmark should stay wider than it is tall")
        assertTrue(image.pixels().any { it ushr 24 != 0 })
    }

    @Test
    fun `tab badge slices are 8 px tall and fully opaque`() {
        assertImage("tab/badge_left.png", 3, 8, hasOpaquePixel = true)
        assertImage("tab/badge_middle.png", 1, 8, hasOpaquePixel = true)
        assertImage("tab/badge_right.png", 3, 8, hasOpaquePixel = true)
        listOf("tab/badge_left.png", "tab/badge_middle.png", "tab/badge_right.png").forEach { path
            ->
            assertTrue(readImage(path).pixels().all { it ushr 24 == 0xFF }, path)
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

    private fun validatePlatformArt(art: Path) {
        val normalizedRoot = art.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedRoot)) {
            "Platform art root is not a directory: $normalizedRoot"
        }
        val actualFiles = linkedSetOf<String>()
        val actualDirectories = linkedSetOf<String>()

        Files.walk(normalizedRoot).use { paths ->
            paths.forEach { path ->
                if (path == normalizedRoot) return@forEach
                val relative = safeRelativePath(normalizedRoot, path)
                when {
                    Files.isSymbolicLink(path) ->
                        error("Platform art must not contain symbolic links: $relative")
                    Files.isDirectory(path) -> actualDirectories += relative
                    Files.isRegularFile(path) -> actualFiles += relative
                    else -> error("Platform art contains a non-regular file: $relative")
                }
            }
        }

        require(EXPECTED_PLATFORM_DIRECTORIES == actualDirectories) {
            "Unexpected platform art directories: expected $EXPECTED_PLATFORM_DIRECTORIES, got $actualDirectories"
        }
        require(EXPECTED_PLATFORM_FILES == actualFiles) {
            "Unexpected platform art files: expected $EXPECTED_PLATFORM_FILES, got $actualFiles"
        }
    }

    private fun safeRelativePath(root: Path, path: Path): String {
        val relative = root.relativize(path.toAbsolutePath().normalize()).normalize()
        require(!relative.isAbsolute && !relative.startsWith("..")) {
            "Unsafe platform art path: $path"
        }
        return relative.joinToString("/") { it.toString() }
    }

    private fun assertNoUnlistedExampleCopy(exampleArt: Path) {
        val exampleHashes =
            Files.walk(exampleArt).use { paths ->
                paths.filter(Files::isRegularFile).toList().associateBy(::sha256)
            }
        actualPlatformFiles().forEach { destination ->
            val approved = APPROVED_ORIGINS[destination]
            val matchingExample = exampleHashes[sha256(platformArt.resolve(destination))]
            require(
                matchingExample == null ||
                    approved?.sha256 == sha256(platformArt.resolve(destination))
            ) {
                "Unlisted library-gui Example asset copied to $destination from $matchingExample"
            }
        }
    }

    private fun actualPlatformFiles(): Set<String> {
        validatePlatformArt(platformArt)
        return EXPECTED_PLATFORM_FILES - setOf("ASSET_ORIGINS.json", "LICENSE")
    }

    private fun copyPlatformArt(): Path {
        val copy = Files.createTempDirectory("grounds-platform-art-")
        Files.walk(platformArt).use { paths ->
            paths.forEach { source ->
                val destination = copy.resolve(platformArt.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(destination)
                else Files.copy(source, destination)
            }
        }
        return copy
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
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
                "tab/logo.png",
                "tab/badge_left.png",
                "tab/badge_middle.png",
                "tab/badge_right.png",
                "menu/panel.png",
                "menu/settings_panel.png",
                "menu/divider.png",
                "menu/hit_1.png",
                "menu/hit_128.png",
                "menu/hit_16.png",
                "menu/hit_2.png",
                "menu/hit_256.png",
                "menu/hit_32.png",
                "menu/hit_4.png",
                "menu/hit_64.png",
                "menu/hit_8.png",
                "menu/settings_panel_2.png",
                "menu/nav_panel_2.png",
                "menu/row_wide_l.png",
                "menu/row_wide_r.png",
                "menu/nav_panel.png",
                "menu/tabs_content_panel.png",
                "menu/nav_button.png",
                "menu/nav_selected.png",
                "menu/region_panel_l.png",
                "menu/region_panel_r.png",
                "menu/map_sea_l.png",
                "menu/map_sea_r.png",
                "menu/map2_na.png",
                "menu/map2_sa.png",
                "menu/map2_eu.png",
                "menu/map2_af.png",
                "menu/map2_as.png",
                "menu/map2_oc.png",
                "menu/map2_na_picked.png",
                "menu/map2_eu_picked.png",
                "menu/map2_as_picked.png",
                "menu/map2_oc_picked.png",
                "menu/region_panel.png",
                "menu/map_sea.png",
                "menu/map_na.png",
                "menu/map_sa.png",
                "menu/map_eu.png",
                "menu/map_af.png",
                "menu/map_as.png",
                "menu/map_oc.png",
                "menu/map_na_picked.png",
                "menu/map_eu_picked.png",
                "menu/map_as_picked.png",
                "menu/map_oc_picked.png",
                "menu/map_zone.png",
                "menu/row.png",
                "menu/toggle_on.png",
                "menu/toggle_off.png",
                "menu/arrow_left.png",
                "menu/arrow_right.png",
            )
        val EXPECTED_PLATFORM_FILES =
            ART_FILES.toSet() + setOf("pack.png", "ASSET_ORIGINS.json", "LICENSE")
        val EXPECTED_PLATFORM_DIRECTORIES =
            setOf("panels", "icons", "tooltips", "frames", "tab", "menu")
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
  },
  "firstPartyOriginals": {
    "tab/logo.png": {
      "source": "brand/logo-text-noglow.png"
    },
    "tab/badge_left.png": {
      "source": "original"
    },
    "tab/badge_middle.png": {
      "source": "original"
    },
    "tab/badge_right.png": {
      "source": "original"
    },
    "menu/panel.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/settings_panel.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/divider.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_1.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_128.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_16.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_2.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_256.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_32.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_4.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_64.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/hit_8.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/settings_panel_2.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/nav_panel_2.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/row_wide_l.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/row_wide_r.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/nav_panel.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/tabs_content_panel.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/nav_button.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/nav_selected.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/region_panel_l.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/region_panel_r.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_sea_l.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_sea_r.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_na.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_sa.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_eu.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_af.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_as.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_oc.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_na_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_eu_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_as_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map2_oc_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/region_panel.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_sea.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_na.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_sa.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_eu.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_af.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_as.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_oc.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_na_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_eu_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_as_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_oc_picked.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/map_zone.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/row.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/toggle_on.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/toggle_off.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/arrow_left.png": {
      "source": "plugin-lobby MenuPack (generated)"
    },
    "menu/arrow_right.png": {
      "source": "plugin-lobby MenuPack (generated)"
    }
  }
}
"""
                .trim()
    }
}
