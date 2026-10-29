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
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},{"type":"bitmap","file":"grounds:font/sidebar_cap.png","height":32,"ascent":23,"chars":["${u(CAP)}"]},{"type":"bitmap","file":"grounds:font/sidebar_logo.png","height":26,"ascent":18,"chars":["${u(LOGO)}"]},{"type":"bitmap","file":"grounds:font/sidebar_bar.png","height":18,"ascent":7,"chars":["${u(BAR)}"]}]}"""
    }

    /**
     * Separate textures keep the wordmark at 3x resolution within Minecraft's 256px glyph atlas.
     */
    fun textures(logo: BufferedImage): Map<String, ByteArray> {
        val cap = BufferedImage(180, 64, BufferedImage.TYPE_INT_ARGB)
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
                        lineTo(86.0, 0.0)
                        curveTo(88.21, 0.0, 90.0, 1.79, 90.0, 4.0)
                        lineTo(90.0, 24.0)
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
        return mapOf(
            "sidebar_cap.png" to png(cap),
            "sidebar_logo.png" to png(wordmark),
            "sidebar_bar.png" to png(bar),
        )
    }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use {
            check(ImageIO.write(image, "png", it))
            it.toByteArray()
        }

    private fun u(value: Int) = "\\u%04X".format(value)
}
