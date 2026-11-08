package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackEntry
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Compact queue HUD. Codepoints and private font names are a wire contract with plugin-match. */
internal object QueueFont {
    const val WIDTH = 160
    private const val HEIGHT = 6
    private const val DEEP = 0x181D20
    private const val SURFACE = 0x272E32
    private const val PRIMARY = 0xF3F0EA
    private const val SECONDARY = 0xC1B9AA
    private const val ENERGY = 0x67D9EC
    private val energyShade = mix(SURFACE, ENERGY, .45)
    private val resources by lazy { create() }

    fun textures(): Map<String, ByteArray> = resources.first

    fun fonts(): Map<String, String> = resources.second

    fun entries(): List<PackEntry> =
        fonts().map { (name, json) -> PackEntry.text("assets/grounds/font/$name", json) } +
            textures().map { (name, bytes) ->
                PackEntry.bytes("assets/grounds/textures/font/$name", bytes)
            }

    private fun create(): Pair<Map<String, ByteArray>, Map<String, String>> {
        val source = linkedMapOf<String, BufferedImage>()
        val glyphs = linkedMapOf<Int, String>()
        repeat(24) { frame ->
            val name = "search_%02d".format(Locale.ROOT, frame)
            source[name] = rail(frame)
            glyphs[0xE700 + frame] = name
        }
        source["found"] = rail(null)
        glyphs[0xE720] = "found"
        repeat(2) { frame ->
            val name = "preparing_$frame"
            source[name] = rail(null)
            glyphs[0xE721 + frame] = name
        }
        source["people"] = people()
        glyphs[0xE730] = "people"
        val atlases =
            QueueFontSources.atlases.associateWith { atlas ->
                val bytes =
                    QueueFont::class.java.getResourceAsStream("queue-source/${atlas.file}")?.use {
                        it.readAllBytes()
                    } ?: error("Missing queue font source ${atlas.file}")
                val digest =
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(Locale.ROOT, it)
                    }
                check(digest == atlas.sha256) { "Queue font source changed: ${atlas.file}" }
                ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Invalid queue font source")
            }
        val textures = linkedMapOf<String, ByteArray>()
        val fonts = linkedMapOf<String, String>()
        source.forEach { (name, image) -> textures["queue_$name.png"] = png(image) }
        fonts["queue.json"] = font(glyphs, null)
        repeat(25) { level ->
            val prefix = "queue_fade_%02d".format(Locale.ROOT, level)
            source.forEach { (name, image) ->
                textures["${prefix}_$name.png"] = png(fade(image, level))
            }
            atlases.forEach { (atlas, image) ->
                textures["${prefix}_${atlas.file}"] = png(fade(image, level))
            }
            fonts["$prefix.json"] = font(glyphs, level)
        }
        return textures to fonts
    }

    private fun font(glyphs: Map<Int, String>, level: Int?): String {
        val prefix = if (level == null) "queue" else "queue_fade_%02d".format(Locale.ROOT, level)
        val advances = buildList {
            add("\" \":4")
            add("\"\\u00A0\":4")
            for (i in 0..9) {
                add("\"${u(0xE740 + 2 * i)}\":${1 shl i}")
                add("\"${u(0xE741 + 2 * i)}\":${-(1 shl i)}")
            }
        }
        val providers =
            mutableListOf("""{"type":"space","advances":{${advances.joinToString(",")}}}""")
        glyphs.forEach { (code, name) ->
            val height = if (name == "people") 7 else HEIGHT
            val ascent = if (name == "people") 7 else -3
            providers +=
                """{"type":"bitmap","file":"grounds:font/${prefix}_$name.png","height":$height,"ascent":$ascent,"chars":["${u(code)}"]}"""
        }
        if (level != null)
            QueueFontSources.atlases.forEach { atlas ->
                val rows =
                    atlas.chars.joinToString(",") { row ->
                        "\"" + row.map { u(it.code) }.joinToString("") + "\""
                    }
                providers +=
                    """{"type":"bitmap","file":"grounds:font/${prefix}_${atlas.file}","height":${atlas.height},"ascent":${atlas.ascent},"chars":[$rows]}"""
            }
        providers += """{"type":"reference","id":"minecraft:default"}"""
        return """{"providers":[${providers.joinToString(",")}]}"""
    }

    private fun rail(frame: Int?): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..4) for (x in 0 until WIDTH) {
            val cap = minOf(x, WIDTH - 1 - x, 3)
            val color =
                when {
                    (y == 0 || y == 4) && cap == 0 -> null
                    y == 0 || y == 4 || cap == 0 -> DEEP
                    (y == 1 || y == 3) && cap in 1..2 -> SECONDARY
                    else -> SURFACE
                }
            if (color != null) image.setRGB(x, y, color or (0xff shl 24))
        }
        if (frame == null) {
            for ((y, color) in listOf(1 to PRIMARY, 2 to ENERGY, 3 to energyShade)) for (x in
                3..156) image.setRGB(x, y, color or (0xff shl 24))
        } else {
            val pos = 3 + (frame / 23.0 * 138).roundToInt()
            for (x in pos..pos + 15) image.setRGB(x, 2, energyShade or (0xff shl 24))
            for (x in pos + 5..pos + 11) image.setRGB(x, 2, ENERGY or (0xff shl 24))
            for (x in pos + 8..pos + 9) image.setRGB(x, 2, PRIMARY or (0xff shl 24))
        }
        for ((x, y, color) in
            listOf(
                Triple(1, 2, PRIMARY),
                Triple(2, 2, ENERGY),
                Triple(2, 1, energyShade),
                Triple(2, 3, energyShade),
            )) {
            image.setRGB(x, y, color or (0xff shl 24))
            image.setRGB(WIDTH - 1 - x, y, color or (0xff shl 24))
        }
        return image
    }

    private fun people(): BufferedImage {
        val image = BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB)
        for ((left, top, right, bottom) in
            listOf(
                listOf(1, 0, 2, 1),
                listOf(4, 1, 5, 2),
                listOf(0, 3, 3, 5),
                listOf(4, 4, 6, 6),
            )) for (x in left..right) for (y in top..bottom) image.setRGB(
            x,
            y,
            SECONDARY or (0xff shl 24),
        )
        return image
    }

    private fun fade(image: BufferedImage, level: Int): BufferedImage {
        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            val alpha = pixel ushr 24
            val faded = if (alpha == 0) 0 else maxOf(1, (alpha * level / 24.0).roundToInt())
            result.setRGB(x, y, (pixel and 0xffffff) or (faded shl 24))
        }
        return result
    }

    private fun mix(first: Int, second: Int, weight: Double): Int =
        listOf(16, 8, 0).fold(0) { result, shift ->
            val channel =
                (((first ushr shift) and 255) * (1 - weight) +
                        ((second ushr shift) and 255) * weight)
                    .roundToInt()
            result or (channel shl shift)
        }

    private fun u(code: Int) = "\\u%04X".format(Locale.ROOT, code)

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use {
            check(ImageIO.write(image, "png", it))
            it.toByteArray()
        }
}
