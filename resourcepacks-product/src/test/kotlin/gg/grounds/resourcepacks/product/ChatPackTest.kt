package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatPackTest {
    @Test
    fun `signed emoji aliases keep their original colon and have a shared zero width token font`() {
        val definitions = ChatFont.signedAliasFonts().toMap()
        val names = listOf("heart", "smile", "laugh", "thumbsup", "gg", "fire", "creeper", "wrench")
        assertEquals(
            names.map { "assets/grounds/font/chat/alias/$it.json" }.toSet() +
                "assets/grounds/font/chat/alias/control.json",
            definitions.keys,
        )
        names.forEach { name ->
            val value = definitions.getValue("assets/grounds/font/chat/alias/$name.json")
            assertTrue("\"file\":\"grounds:font/chat/$name.png\"" in value, value)
            assertTrue("\"chars\":[\":\"]" in value, value)
            assertTrue("\"ascent\":7" in value && "\"height\":8" in value, value)
            assertTrue(
                "reference" !in value,
                "Alias fonts must not render additional token characters",
            )
        }
        val control =
            tools.jackson.databind.json
                .JsonMapper()
                .readTree(definitions.getValue("assets/grounds/font/chat/alias/control.json"))
        val provider = control["providers"][0]
        assertEquals("space", provider["type"].asString())
        val advances = provider["advances"]
        assertEquals(
            (32..126).map { it.toChar().toString() }.toSet(),
            advances.properties().map { it.key }.toSet(),
        )
        advances.properties().forEach { (character, width) ->
            assertEquals(0, width.intValue(), character)
        }
    }

    @Test
    fun `platform pack owns the chat font without overriding the vanilla font`() {
        ProductGraph.secureReleaseInputs().use { secured ->
            val parent = java.nio.file.Files.createTempDirectory("chat-pack-")
            val scratch = SecureOwnedDirectory.create(parent, ".chat-pack-")
            try {
                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val platform =
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.PLATFORM }.scratchFile
                    )
                val content =
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.CONTENT }.scratchFile
                    )

                ZipFile(content.toFile()).use { zip ->
                    assertTrue(zip.getEntry(CHAT_FONT) == null)
                    CHAT_TEXTURES.forEach { assertTrue(zip.getEntry(it) == null, it) }
                    assertTrue(
                        zip.entries().asSequence().none {
                            it.name.startsWith("assets/grounds/font/chat/alias/")
                        }
                    )
                }
                ZipFile(platform.toFile()).use { zip ->
                    listOf(
                            "heart",
                            "smile",
                            "laugh",
                            "thumbsup",
                            "gg",
                            "fire",
                            "creeper",
                            "wrench",
                            "control",
                        )
                        .forEach { name ->
                            assertNotNull(
                                zip.getEntry("assets/grounds/font/chat/alias/$name.json"),
                                name,
                            )
                        }
                    assertTrue(zip.entries().asSequence().none { it.name == VANILLA_DEFAULT_FONT })
                    val font =
                        zip.getInputStream(assertNotNull(zip.getEntry(CHAT_FONT)))
                            .reader()
                            .readText()
                    assertTrue("\"type\":\"reference\"" in font, font)
                    assertTrue("\"id\":\"minecraft:default\"" in font, font)
                    assertEquals(9, Regex("\"type\":\"bitmap\"").findAll(font).count())

                    EXPECTED_GLYPHS.forEachIndexed { index, (name, codepoint) ->
                        val texture = "grounds:font/chat/$name.png"
                        assertTrue("\"file\":\"$texture\"" in font, font)
                        assertTrue("\"ascent\":7" in font, font)
                        assertTrue("\"height\":8" in font, font)
                        val encoded = "\\u%04X".format(codepoint)
                        assertEquals(1, Regex(Regex.escape(encoded)).findAll(font).count(), encoded)
                        assertEquals(0xE100 + index, codepoint)
                    }

                    val logoBytes =
                        zip.getInputStream(
                                assertNotNull(
                                    zip.getEntry("assets/grounds/textures/font/chat/logo.png")
                                )
                            )
                            .readAllBytes()
                    assertEquals(APPROVED_LOGO_SHA256, sha256(logoBytes))
                    val logo = assertNotNull(ImageIO.read(ByteArrayInputStream(logoBytes)))
                    assertEquals(128, logo.width)
                    assertEquals(128, logo.height)
                    val logoPixels = logo.pixels().toList()
                    assertTrue(logoPixels.any { it ushr 24 == 0 })
                    assertTrue(logoPixels.any { it ushr 24 != 0 })
                    assertTrue(
                        logoPixels.filter { it ushr 24 != 0 }.map { it and 0xFFFFFF }.toSet().size >
                            8
                    )

                    CHAT_TEXTURES.drop(1).forEach { path ->
                        val bytes =
                            zip.getInputStream(assertNotNull(zip.getEntry(path))).readAllBytes()
                        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), path)
                        assertEquals(8, image.width, path)
                        assertEquals(8, image.height, path)
                        val pixels = image.pixels().toList()
                        assertTrue(pixels.any { it ushr 24 == 0 }, path)
                        assertTrue(pixels.any { it ushr 24 == 0xFF }, path)
                        assertTrue(
                            pixels
                                .filter { it ushr 24 != 0 }
                                .map { it and 0xFFFFFF }
                                .toSet()
                                .size >= 2,
                            path,
                        )
                    }
                }
            } finally {
                scratch.close()
                parent.toFile().deleteRecursively()
            }
        }
    }

    private fun java.awt.image.BufferedImage.pixels(): Sequence<Int> = sequence {
        for (y in 0 until height) for (x in 0 until width) yield(getRGB(x, y))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CHAT_FONT = "assets/grounds/font/chat.json"
        const val VANILLA_DEFAULT_FONT = "assets/minecraft/font/default.json"
        const val APPROVED_LOGO_SHA256 =
            "319a3bacb127ab5047b0373f86dff54934140b97192fb9d5552209be3fea210a"
        val EXPECTED_GLYPHS =
            listOf(
                "logo" to 0xE100,
                "heart" to 0xE101,
                "smile" to 0xE102,
                "laugh" to 0xE103,
                "thumbsup" to 0xE104,
                "gg" to 0xE105,
                "fire" to 0xE106,
                "creeper" to 0xE107,
                "wrench" to 0xE108,
            )
        val CHAT_TEXTURES =
            EXPECTED_GLYPHS.map { (name, _) -> "assets/grounds/textures/font/chat/$name.png" }
    }
}
