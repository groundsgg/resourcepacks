package gg.grounds.resourcepacks.product

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The compact brand and emoji glyphs used by `grounds:chat`. */
internal object ChatFont {
    const val LOGO = 0xE100
    const val HEART = 0xE101
    const val SMILE = 0xE102
    const val LAUGH = 0xE103
    const val THUMBSUP = 0xE104
    const val GG = 0xE105
    const val FIRE = 0xE106
    const val CREEPER = 0xE107
    const val WRENCH = 0xE108

    private val FACE =
        mapOf('Y' to color(0xFFFFC928), 'O' to color(0xFFFFE066), 'D' to color(0xFF5B3A29))

    private val glyphs =
        listOf(
            Glyph(
                "heart",
                HEART,
                mapOf('R' to color(0xFF9E2745), 'L' to color(0xFFFF4D6D)),
                listOf(
                    ".RR..RR.",
                    "RLLRRLLR",
                    "RLLLLLLR",
                    ".RLLLLR.",
                    "..RLLR..",
                    "...RR...",
                    "........",
                    "........",
                ),
            ),
            Glyph(
                "smile",
                SMILE,
                FACE,
                listOf(
                    "..YYYY..",
                    ".YOOOOY.",
                    "YODOODOY",
                    "YOOOOOOY",
                    "YOODDOOY",
                    "YODDDDOY",
                    ".YOOOOY.",
                    "..YYYY..",
                ),
            ),
            Glyph(
                "laugh",
                LAUGH,
                FACE + ('C' to color(0xFF42D9F5)),
                listOf(
                    "C.YYYY.C",
                    "CYOOOOYC",
                    "YODOODOY",
                    "YOOOOOOY",
                    "YODDDDOY",
                    "YODDDDOY",
                    ".YOOOOY.",
                    "..YYYY..",
                ),
            ),
            Glyph(
                "thumbsup",
                THUMBSUP,
                mapOf('S' to color(0xFFFFC66D), 'B' to color(0xFFA9683A)),
                listOf(
                    "...SS...",
                    "..SSS...",
                    "..SSS...",
                    ".SSSSSS.",
                    "SSSSSSSB",
                    "BSSSSSSB",
                    ".BBBBBB.",
                    "........",
                ),
            ),
            Glyph(
                "gg",
                GG,
                mapOf('T' to color(0xFF3DE6DB), 'L' to color(0xFFB5FFF9)),
                listOf(
                    ".TT..TT.",
                    "TL..TL..",
                    "T...T...",
                    "T.TTT.TT",
                    "T..LT..L",
                    ".TTT.TTT",
                    "........",
                    "........",
                ),
            ),
            Glyph(
                "fire",
                FIRE,
                mapOf('R' to color(0xFFE64A19), 'O' to color(0xFFFF8F00), 'Y' to color(0xFFFFD54F)),
                listOf(
                    "....R...",
                    "...RRO..",
                    "..ROOO..",
                    ".ROOORR.",
                    ".OOYYOR.",
                    "ROYYYYOR",
                    ".ROYYOR.",
                    "..RRRR..",
                ),
            ),
            Glyph(
                "creeper",
                CREEPER,
                mapOf('G' to color(0xFF57A64E), 'L' to color(0xFF8DCE63), 'D' to color(0xFF183D1B)),
                listOf(
                    ".GGGGGG.",
                    "GLLLLLLG",
                    "GLDDDDLG",
                    "GLDDDDLG",
                    "GLLDDLLG",
                    "GLDDDDLG",
                    "GLDGGDLG",
                    ".GGGGGG.",
                ),
            ),
            Glyph(
                "wrench",
                WRENCH,
                mapOf('S' to color(0xFFB8C2CC), 'D' to color(0xFF59636E)),
                listOf(
                    "SS....SS",
                    ".SS..SS.",
                    "..SSSS..",
                    "...SS...",
                    "..SSS...",
                    ".SS.SS..",
                    "SS...SS.",
                    "D.....DD",
                ),
            ),
        )

    fun json(): String {
        val providers =
            listOf(bitmap("logo", LOGO)) +
                glyphs.map { bitmap(it.name, it.codepoint) } +
                """{"type":"reference","id":"minecraft:default"}"""
        return """{"providers":[${providers.joinToString(",")}]}"""
    }

    fun emojiTextures(): List<Pair<String, ByteArray>> =
        glyphs.map { glyph -> glyph.name to texture(glyph) }

    /** Keeps the signed alias text intact while drawing its first colon as the graphic. */
    fun signedAliasFonts(): List<Pair<String, String>> {
        val aliases =
            glyphs.map { glyph ->
                "assets/grounds/font/chat/alias/${glyph.name}.json" to
                    """{"providers":[{"type":"bitmap","file":"grounds:font/chat/${glyph.name}.png","ascent":7,"height":8,"chars":[":"]}]}"""
            }
        val advances =
            (32..126).joinToString(",") { codepoint ->
                val character =
                    when (val value = codepoint.toChar()) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        else -> value.toString()
                    }
                "\"$character\":0"
            }
        return aliases +
            ("assets/grounds/font/chat/alias/control.json" to
                """{"providers":[{"type":"space","advances":{$advances}}]}""")
    }

    private fun bitmap(name: String, codepoint: Int): String =
        """{"type":"bitmap","file":"grounds:font/chat/$name.png","ascent":7,"height":8,"chars":["${u(codepoint)}"]}"""

    private fun texture(glyph: Glyph): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        glyph.pixels.forEachIndexed { y, row ->
            check(row.length == 8)
            row.forEachIndexed { x, pixel ->
                if (pixel != '.') image.setRGB(x, y, glyph.palette.getValue(pixel))
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun u(codepoint: Int): String = "\\u%04X".format(codepoint)

    private fun color(argb: Long): Int = argb.toInt()

    private data class Glyph(
        val name: String,
        val codepoint: Int,
        val palette: Map<Char, Int>,
        val pixels: List<String>,
    ) {
        init {
            check(pixels.size == 8)
        }
    }
}
