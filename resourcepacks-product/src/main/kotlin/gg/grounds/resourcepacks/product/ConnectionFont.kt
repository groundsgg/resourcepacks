package gg.grounds.resourcepacks.product

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** First-party connection pictograms, with hard pixels and the Minecraft Mini-CD palette. */
internal object ConnectionFont {
    private val icons =
        listOf(
            listOf(
                "..........",
                "...GGGG...",
                "..GWWWWG..",
                "..GW..WG..",
                "..GWEEWG..",
                "...GWWG...",
                "....GG....",
                "....CC....",
                "..........",
                "..........",
            ),
            listOf(
                "..........",
                ".GG....GG.",
                ".GEG..GEG.",
                ".GG.CC.GG.",
                "...C..C...",
                "....CC....",
                "...GEEG...",
                "...GGGG...",
                "..........",
                "..........",
            ),
            listOf(
                "..........",
                ".CCCCCCCC.",
                ".CWWWW.EC.",
                ".CCCCCCCC.",
                "..........",
                ".CCCCCCCC.",
                ".CWWWW.EC.",
                ".CCCCCCCC.",
                "..........",
                "..........",
            ),
        )
    private val colors =
        mapOf(
            'G' to 0xFFE7B84B.toInt(),
            'C' to 0xFFDDA16F.toInt(),
            'W' to 0xFFF3F0EA.toInt(),
            'E' to 0xFF67D9EC.toInt(),
        )

    fun json(): String =
        """{"providers":[{"type":"bitmap","file":"grounds:font/connection_icons.png","ascent":8,"height":10,"chars":["\uE410\uE411\uE412"]},{"type":"reference","id":"minecraft:default"}]}"""

    fun texture(): ByteArray {
        val image = BufferedImage(30, 10, BufferedImage.TYPE_INT_ARGB)
        icons.forEachIndexed { index, rows ->
            require(rows.size == 10 && rows.all { it.length == 10 })
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, pixel ->
                    if (pixel != '.') image.setRGB(index * 10 + x, y, colors.getValue(pixel))
                }
            }
        }
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, "png", out))
            out.toByteArray()
        }
    }
}
