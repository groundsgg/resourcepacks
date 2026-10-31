package gg.grounds.resourcepacks.product

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Native sidebar title overlays. Coordinates are measured with the Minecraft 26.3 renderer. */
internal object SidebarFont {
    const val CAP = 0xE300
    const val BAR = 0xE301
    const val LOGO = 0xE302
    const val CROWN = 0xE303
    const val COIN = 0xE304
    const val FRIENDS = 0xE305
    const val SEPARATOR = 0xE306
    const val FOOTER_SEPARATOR = 0xE307
    const val SPACE_BASE = 0xE310

    // The header anchor has no pixels: one native body quad supplies the entire background.
    fun json(): String {
        val advances =
            (0..10).flatMap { i ->
                listOf(
                    "\"${u(SPACE_BASE + 2 * i)}\":${1 shl i}",
                    "\"${u(SPACE_BASE + 2 * i + 1)}\":${-(1 shl i)}",
                )
            } + listOf("\"${u(CAP)}\":111")
        val icons =
            listOf(CROWN to "crown", COIN to "coin", FRIENDS to "friends").joinToString(",") {
                (char, name) ->
                """{"type":"bitmap","file":"grounds:font/sidebar_$name.png","height":10,"ascent":4,"chars":["${u(char)}"]}"""
            }
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},{"type":"bitmap","file":"grounds:font/sidebar_logo.png","height":26,"ascent":18,"chars":["${u(LOGO)}"]},{"type":"bitmap","file":"grounds:font/sidebar_bar.png","height":18,"ascent":7,"chars":["${u(BAR)}"]},$icons,{"type":"bitmap","file":"grounds:font/sidebar_separator.png","height":15,"ascent":10,"chars":["${u(SEPARATOR)}"]},{"type":"bitmap","file":"grounds:font/sidebar_separator.png","height":15,"ascent":6,"chars":["${u(FOOTER_SEPARATOR)}"]}]}"""
    }

    /** Shift only the ASCII footer down four pixels, using the client's own vanilla texture. */
    fun footerJson(): String {
        val rows =
            (0..15).joinToString(",") { row ->
                "\"" +
                    (0..15).joinToString("") { col ->
                        val code = row * 16 + col
                        u(if (code in 32..126) code else 0)
                    } +
                    "\""
            }
        return """{"providers":[{"type":"space","advances":{" ":4}},{"type":"bitmap","file":"minecraft:font/ascii.png","height":8,"ascent":3,"chars":[$rows]}]}"""
    }

    /**
     * Separate textures keep the wordmark at 3x resolution within Minecraft's 256px glyph atlas.
     */
    fun textures(logo: BufferedImage): Map<String, ByteArray> {
        val wordmark = wordmark(logo)
        val bar = BufferedImage(1, 18, BufferedImage.TYPE_INT_ARGB)
        repeat(18) { bar.setRGB(0, it, -1) }
        val separator = BufferedImage(180, 30, BufferedImage.TYPE_INT_ARGB)
        separator.createGraphics().let { g ->
            try {
                g.color = java.awt.Color.WHITE
                g.fillRect(0, 8, 82, 2)
                g.fillRect(98, 8, 82, 2)
                val diamond =
                    java.awt.Polygon(intArrayOf(90, 96, 90, 84), intArrayOf(4, 9, 14, 9), 4)
                g.fillPolygon(diamond)
            } finally {
                g.dispose()
            }
        }
        return mapOf(
            "sidebar_logo.png" to png(wordmark),
            "sidebar_bar.png" to png(bar),
            "sidebar_separator.png" to png(separator),
            "sidebar_crown.png" to
                icon(
                    listOf(
                        "..........",
                        "G...G....G",
                        "GG..GG..GG",
                        "GGG.GG.GGG",
                        ".GGGGGGGG.",
                        ".GYYYYYYG.",
                        ".GYYYYYYG.",
                        ".GYYYYYYG.",
                        ".GGGGGGGG.",
                        "..........",
                    )
                ),
            "sidebar_coin.png" to
                icon(
                    listOf(
                        "...GGGG...",
                        ".GGGYYGGG.",
                        ".GGYYYYGG.",
                        "GGYYHYYYGG",
                        "GYYYHYYYYG",
                        "GYYYHYYYYG",
                        "GGYYHYYYGG",
                        ".GGYYYYGG.",
                        ".GGGYYGGG.",
                        "...GGGG...",
                    )
                ),
            "sidebar_friends.png" to
                icon(
                    listOf(
                        "..CC...CC.",
                        ".CCCC.CCCC",
                        ".CCCC.CCCC",
                        "..CC...CC.",
                        "..........",
                        ".CCCC.CCCC",
                        "CCCCCCCCCC",
                        "CCCCCCCCCC",
                        "CCCCCCCCCC",
                        "..........",
                    )
                ),
        )
    }

    /**
     * Average source-pixel coverage in premultiplied alpha. Fine stone/gem detail must contribute
     * its area rather than alias into bright specks, and transparent RGB must not tint the edges.
     * Keep the native-tested 3x raster and transparent glyph padding.
     */
    private fun wordmark(source: BufferedImage): BufferedImage {
        val texture = BufferedImage(216, 78, BufferedImage.TYPE_INT_ARGB)
        val scaleX = source.width / 216.0
        val scaleY = source.height / 42.0
        repeat(42) { y ->
            val top = y * scaleY
            val bottom = (y + 1) * scaleY
            repeat(216) { x ->
                val left = x * scaleX
                val right = (x + 1) * scaleX
                var alpha = 0.0
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                for (sy in top.toInt() until min(source.height, ceil(bottom).toInt())) {
                    for (sx in left.toInt() until min(source.width, ceil(right).toInt())) {
                        val coverage =
                            (min(right, sx + 1.0) - max(left, sx.toDouble())) *
                                (min(bottom, sy + 1.0) - max(top, sy.toDouble()))
                        val pixel = source.getRGB(sx, sy)
                        val weight = coverage * ((pixel ushr 24) / 255.0)
                        alpha += weight
                        red += weight * ((pixel ushr 16) and 255)
                        green += weight * ((pixel ushr 8) and 255)
                        blue += weight * (pixel and 255)
                    }
                }
                val a = (255 * alpha / (scaleX * scaleY)).roundToInt()
                val r = if (alpha > 0) (red / alpha).roundToInt() else 0
                val g = if (alpha > 0) (green / alpha).roundToInt() else 0
                val b = if (alpha > 0) (blue / alpha).roundToInt() else 0
                texture.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return texture
    }

    /** First-party ten-pixel symbols, kept crisp at three texture pixels per GUI pixel. */
    private fun icon(rows: List<String>): ByteArray {
        require(rows.size == 10 && rows.all { it.length == 10 })
        val image = BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, pixel ->
                val color =
                    when (pixel) {
                        'G' -> 0xFFB8B8B8.toInt()
                        'H' -> 0xFFFFF5CE.toInt()
                        'Y',
                        'C' -> -1
                        else -> 0
                    }
                repeat(3) { dy -> repeat(3) { dx -> image.setRGB(x * 3 + dx, y * 3 + dy, color) } }
            }
        }
        return png(image)
    }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use {
            check(ImageIO.write(image, "png", it))
            it.toByteArray()
        }

    private fun u(value: Int) = "\\u%04X".format(value)
}
