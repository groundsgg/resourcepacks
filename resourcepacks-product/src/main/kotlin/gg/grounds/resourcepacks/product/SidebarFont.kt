package gg.grounds.resourcepacks.product

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Native sidebar title overlays. Coordinates are measured with the Minecraft 26.3 renderer. */
internal object SidebarFont {
    const val CAP = 0xE300
    const val BAR = 0xE301
    const val LOGO = 0xE302
    const val CROWN = 0xE303
    const val COIN = 0xE304
    const val FRIENDS = 0xE305
    const val SEPARATOR = 0xE306
    const val SPACE_BASE = 0xE310

    // The native title is empty. The cap joins the first body row, with 5px around the logo.
    fun json(): String {
        val advances =
            (0..10).flatMap { i ->
                listOf(
                    "\"${u(SPACE_BASE + 2 * i)}\":${1 shl i}",
                    "\"${u(SPACE_BASE + 2 * i + 1)}\":${-(1 shl i)}",
                )
            }
        val icons =
            listOf(CROWN to "crown", COIN to "coin", FRIENDS to "friends").joinToString(",") {
                (char, name) ->
                """{"type":"bitmap","file":"grounds:font/sidebar_$name.png","height":10,"ascent":4,"chars":["${u(char)}"]}"""
            }
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},{"type":"bitmap","file":"grounds:font/sidebar_cap.png","height":32,"ascent":23,"chars":["${u(CAP)}"]},{"type":"bitmap","file":"grounds:font/sidebar_logo.png","height":26,"ascent":18,"chars":["${u(LOGO)}"]},{"type":"bitmap","file":"grounds:font/sidebar_bar.png","height":18,"ascent":7,"chars":["${u(BAR)}"]},$icons,{"type":"bitmap","file":"grounds:font/sidebar_separator.png","height":9,"ascent":8,"chars":["${u(SEPARATOR)}"]}]}"""
    }

    /**
     * Separate textures keep the wordmark at 3x resolution within Minecraft's 256px glyph atlas.
     */
    fun textures(logo: BufferedImage): Map<String, ByteArray> {
        val cap = BufferedImage(220, 64, BufferedImage.TYPE_INT_ARGB)
        cap.createGraphics().let { g ->
            try {
                g.scale(2.0, 2.0)
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g.color = java.awt.Color(0, 0, 0, 102)
                val outline =
                    java.awt.geom.Path2D.Double().apply {
                        moveTo(0.0, 4.0)
                        curveTo(0.0, 1.79, 1.79, 0.0, 4.0, 0.0)
                        lineTo(106.0, 0.0)
                        curveTo(108.21, 0.0, 110.0, 1.79, 110.0, 4.0)
                        lineTo(110.0, 24.0)
                        lineTo(0.0, 24.0)
                        closePath()
                    }
                g.fill(outline)
            } finally {
                g.dispose()
            }
        }
        val wordmark = BufferedImage(216, 78, BufferedImage.TYPE_INT_ARGB)
        wordmark.createGraphics().let { g ->
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                )
                g.drawImage(logo, 0, 0, 216, 42, null)
            } finally {
                g.dispose()
            }
        }
        val bar = BufferedImage(1, 18, BufferedImage.TYPE_INT_ARGB)
        repeat(18) { bar.setRGB(0, it, -1) }
        val separator = BufferedImage(180, 18, BufferedImage.TYPE_INT_ARGB)
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
            "sidebar_cap.png" to png(cap),
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
