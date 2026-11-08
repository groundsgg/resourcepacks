package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueuePackTest {
    @Test
    fun `resource names and font JSON are independent of build host locale`() {
        val original = java.util.Locale.getDefault()
        val create = QueueFont::class.java.getDeclaredMethod("create").apply { isAccessible = true }
        val expected = (create.invoke(QueueFont) as Pair<*, *>).second
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals(expected, (create.invoke(QueueFont) as Pair<*, *>).second)
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `queue font is contributed only to platform with no vanilla overrides`() {
        val packs = ProductGraph.packs
        val path = "assets/grounds/font/queue.json"
        assertTrue(
            packs.last().contributions.flatMap { it.entries }.any { it.path.toString() == path }
        )
        assertTrue(
            packs.first().contributions.flatMap { it.entries }.none { it.path.toString() == path }
        )
        assertTrue(QueueFont.entries().all { it.path.toString().startsWith("assets/grounds/") })
    }

    @Test
    fun `all private fonts resolve textures and preserve native accented provider geometry`() {
        val textures = QueueFont.textures()
        val fonts = QueueFont.fonts()
        assertEquals(26, fonts.size)
        for ((name, json) in fonts) {
            assertTrue("minecraft:default" in json, name)
            Regex("\"file\":\"grounds:font/([^\"]+)\"").findAll(json).forEach {
                assertTrue(it.groupValues[1] in textures, "Missing ${it.groupValues[1]} in $name")
            }
        }
        assertTrue("\"height\":12,\"ascent\":10" in fonts.getValue("queue_fade_24.json"))
        assertTrue("\\u00E9" in fonts.getValue("queue_fade_24.json"))
    }

    @Test
    fun `every rail and opacity variant has mirrored ends and a fixed 160 pixel ink span`() {
        for ((name, bytes) in
            QueueFont.textures().filterKeys {
                "search_" in it || "found" in it || "preparing_" in it
            }) {
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(160, image.width, name)
            assertEquals(6, image.height, name)
            for (x in 0..2) for (y in 0..5) {
                assertEquals(image.getRGB(x, y), image.getRGB(159 - x, y), "$name end $x,$y")
            }
            assertTrue((0..4).any { image.getRGB(159, it) ushr 24 != 0 }, name)
            assertTrue((0..159).all { image.getRGB(it, 5) ushr 24 == 0 }, name)
        }
        val low =
            ImageIO.read(
                ByteArrayInputStream(QueueFont.textures().getValue("queue_fade_00_found.png"))
            )
        val full =
            ImageIO.read(
                ByteArrayInputStream(QueueFont.textures().getValue("queue_fade_24_found.png"))
            )
        assertEquals(1, low.getRGB(10, 2) ushr 24)
        assertEquals(255, full.getRGB(10, 2) ushr 24)
        assertEquals(0x67D9EC, full.getRGB(10, 2) and 0xffffff)
        assertEquals(0xF3F0EA, full.getRGB(10, 1) and 0xffffff)
    }

    @Test
    fun `rail pixels match the approved native prototype`() {
        val expected =
            java.util.Properties().apply {
                QueuePackTest::class
                    .java
                    .getResourceAsStream("queue-approved-rails.properties")
                    .use { load(it) }
            }
        expected.forEach { (key, value) ->
            val image =
                ImageIO.read(ByteArrayInputStream(QueueFont.textures().getValue(key.toString())))
            val pixels = ByteArray(image.width * image.height * 4)
            var at = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                for (shift in listOf(16, 8, 0, 24)) pixels[at++] = (argb ushr shift).toByte()
            }
            val sha =
                MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") {
                    "%02x".format(it)
                }
            assertEquals(value, sha, key.toString())
        }
    }
}
