package gg.grounds.resourcepacks.product

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Native sidebar title overlays. Coordinates are measured with the Minecraft 26.3 renderer. */
internal object SidebarFont {
    const val CAP = 0xE300
    const val BAR = 0xE301
    const val SPACE_BASE = 0xE310

    fun json(): String {
        val advances =
            (0..10).flatMap { i ->
                listOf(
                    "\"${u(SPACE_BASE + 2 * i)}\":${1 shl i}",
                    "\"${u(SPACE_BASE + 2 * i + 1)}\":${-(1 shl i)}",
                )
            }
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},{"type":"bitmap","file":"grounds:font/sidebar_cap.png","height":37,"ascent":37,"chars":["${u(CAP)}"]},{"type":"bitmap","file":"grounds:font/sidebar_bar.png","height":18,"ascent":7,"chars":["${u(BAR)}"]}]}"""
    }

    /** The logo is read from the already held, hash-verified first-party tab artwork. */
    fun textures(logo: BufferedImage): Map<String, ByteArray> {
        val cap = BufferedImage(114, 37, BufferedImage.TYPE_INT_ARGB)
        cap.createGraphics().let { g ->
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g.color = java.awt.Color(0, 0, 0, 102)
                val outline =
                    java.awt.geom.Path2D.Double().apply {
                        moveTo(0.0, 4.0)
                        curveTo(0.0, 1.79, 1.79, 0.0, 4.0, 0.0)
                        lineTo(110.0, 0.0)
                        curveTo(112.21, 0.0, 114.0, 1.79, 114.0, 4.0)
                        lineTo(114.0, 29.0)
                        lineTo(0.0, 29.0)
                        closePath()
                    }
                g.fill(outline)
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                )
                g.drawImage(logo, 9, 6, 96, 19, null)
            } finally {
                g.dispose()
            }
        }
        val bar = BufferedImage(1, 18, BufferedImage.TYPE_INT_ARGB)
        repeat(18) { bar.setRGB(0, it, -1) }
        return mapOf("sidebar_cap.png" to png(cap), "sidebar_bar.png" to png(bar))
    }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use {
            check(ImageIO.write(image, "png", it))
            it.toByteArray()
        }

    private fun u(value: Int) = "\\u%04X".format(value)
}
