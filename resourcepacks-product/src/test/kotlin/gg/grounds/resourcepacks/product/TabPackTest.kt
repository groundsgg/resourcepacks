package gg.grounds.resourcepacks.product

import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TabPackTest {
    @Test
    fun `platform zip contains tab fonts and their textures`() {
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
                    TAB_PATHS.forEach { path -> assertNotNull(zip.getEntry(path), path) }
                    TAB_TEXTURE_DIGESTS.forEach { (path, digest) ->
                        val actual =
                            zip.getInputStream(zip.getEntry(path)).use { input ->
                                MessageDigest.getInstance("SHA-256")
                                    .digest(input.readBytes())
                                    .joinToString("") { "%02x".format(it) }
                            }
                        assertEquals(digest, actual, path)
                    }
                    val font =
                        zip.getInputStream(zip.getEntry("assets/grounds/font/tab.json"))
                            .reader()
                            .readText()
                    assertTrue("\"file\":\"grounds:font/tab_logo.png\"" in font, font)
                    assertTrue("\\uE000" in font || "\uE000" in font, font)
                    (0xE001..0xE003).forEach { codepoint ->
                        val glyph = "\\u%04X".format(codepoint)
                        assertTrue(glyph in font || codepoint.toChar().toString() in font, font)
                    }
                    assertTrue("\"file\":\"minecraft:block/bedrock.png\"" in font, font)
                    assertTrue("\\uE004" in font || "\uE004" in font, font)
                    assertTrue("\"ascent\":7" in font, font)
                    assertTrue("\"height\":8" in font, font)
                    val labels =
                        zip.getInputStream(zip.getEntry("assets/grounds/font/tab_labels.json"))
                            .reader()
                            .readText()
                    assertTrue("\"type\":\"reference\"" in labels, labels)
                    assertTrue("\"id\":\"minecraft:default\"" in labels, labels)
                    assertTrue("\"height\":7" in labels, labels)
                    assertTrue("\"ascent\":7" in labels, labels)
                    val expectedRows =
                        listOf(
                            " ABCDEFGHIJKLMNO",
                            "PQRSTUVWXYZ01234",
                            "56789! .:-+_?/\\u0000\\u0000",
                        )
                    assertTrue(
                        "\"chars\":[${expectedRows.joinToString(",") { "\"$it\"" }}]" in labels,
                        labels,
                    )
                    assertEquals(3, expectedRows.size)
                    assertTrue(expectedRows.all { it.replace("\\u0000", "\u0000").length == 16 })

                    val atlas =
                        zip.getInputStream(
                                zip.getEntry("assets/grounds/textures/font/tab_labels.png")
                            )
                            .use(ImageIO::read)
                    assertEquals(96, atlas.width)
                    assertEquals(21, atlas.height)
                    val glyphs = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789! .:-+_?/"
                    glyphs.forEachIndexed { glyph, character ->
                        val x = (glyph % 16) * 6
                        val y = (glyph / 16) * 7
                        for (column in 0 until 6) {
                            assertEquals(0, atlas.getRGB(x + column, y) ushr 24)
                            assertEquals(0, atlas.getRGB(x + column, y + 6) ushr 24)
                        }
                        val ink =
                            (1..5).flatMap { row ->
                                (0 until 6).mapNotNull { column ->
                                    atlas
                                        .getRGB(x + column, y + row)
                                        .takeIf { it ushr 24 != 0 }
                                        ?.also { assertEquals(0xFFFFFF, it and 0xFFFFFF) }
                                        ?.let { column }
                                }
                            }
                        if (character == ' ') {
                            assertTrue(ink.isEmpty())
                        } else {
                            assertFalse(ink.isEmpty(), character.toString())
                            assertEquals(
                                expectedRightmostInkColumn(character),
                                ink.max(),
                                character.toString(),
                            )
                        }
                    }
                }
            } finally {
                scratch.close()
                parent.toFile().deleteRecursively()
            }
        }
    }

    private companion object {
        fun expectedRightmostInkColumn(character: Char): Int =
            when (character) {
                'I' -> 2
                '!',
                '.',
                ':' -> 0
                '-' -> 2
                else -> 4
            }

        val TAB_PATHS =
            listOf(
                "assets/grounds/font/tab.json",
                "assets/grounds/font/tab_labels.json",
                "assets/grounds/textures/font/tab_logo.png",
                "assets/grounds/textures/font/tab_badge_left.png",
                "assets/grounds/textures/font/tab_badge_middle.png",
                "assets/grounds/textures/font/tab_badge_right.png",
                "assets/grounds/textures/font/tab_labels.png",
            )

        val TAB_TEXTURE_DIGESTS =
            mapOf(
                "assets/grounds/textures/font/tab_logo.png" to
                    "6bbed2d5cceb0c34392d5fca7dbdc7257f989c0fd451e8ef240217de8189a7ae",
                "assets/grounds/textures/font/tab_badge_left.png" to
                    "62d9bea27a1ce0452d1308f26ebdb259f8ebdec036a87db4476fe92c72fcce0d",
                "assets/grounds/textures/font/tab_badge_middle.png" to
                    "7a2c1ae0457ac9ddfd76a4b9b9ae985c1772ffd1afa5f1ba6ed25428c299e6e8",
                "assets/grounds/textures/font/tab_badge_right.png" to
                    "5ce1ffa4f916075b2038951635e8fb700b92df1d0cd4d28abb8123c772754c24",
            )
    }
}
