package gg.grounds.resourcepacks.product

internal object TabFont {
    const val LOGO = 0xE000
    const val BADGE_LEFT = 0xE001
    const val BADGE_MIDDLE = 0xE002
    const val BADGE_RIGHT = 0xE003
    const val BEDROCK = 0xE004
    /** A live voice connection; tinted by the text colour like every white glyph here. */
    const val VOICE_ON = 0xE005
    /** Voice switched off in the mod. */
    const val VOICE_OFF = 0xE006
    /** In the viewer's party. */
    const val PARTY = 0xE007
    const val SPACE_BASE = 0xE010

    private val STEPS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024)

    fun json(): String {
        val advances =
            STEPS.flatMapIndexed { index, step ->
                listOf(
                    "\"${u(SPACE_BASE + index * 2)}\":$step",
                    "\"${u(SPACE_BASE + index * 2 + 1)}\":${-step}",
                )
            }
        val bitmaps =
            listOf(
                bitmap("tab_logo.png", ascent = 21, height = 26, LOGO),
                bitmap("tab_badge_left.png", ascent = 7, height = 8, BADGE_LEFT),
                bitmap("tab_badge_middle.png", ascent = 7, height = 8, BADGE_MIDDLE),
                bitmap("tab_badge_right.png", ascent = 7, height = 8, BADGE_RIGHT),
                bitmapFile("minecraft:block/bedrock.png", ascent = 7, height = 8, BEDROCK),
                TabIcons.provider(),
            )
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},${bitmaps.joinToString(",")}]}"""
    }

    private fun bitmap(file: String, ascent: Int, height: Int, codepoint: Int): String =
        bitmapFile("grounds:font/$file", ascent, height, codepoint)

    private fun bitmapFile(file: String, ascent: Int, height: Int, codepoint: Int): String =
        """{"type":"bitmap","file":"$file","ascent":$ascent,"height":$height,"chars":["${u(codepoint)}"]}"""

    private fun u(codepoint: Int): String = "\\u%04X".format(codepoint)
}

/**
 * Small status icons for the tab list and nametags, drawn into one atlas: 7×7 white pixels in an
 * 8-pixel cell, so they sit on the text baseline like the rank badge and advance 8 pixels.
 */
internal object TabIcons {
    private const val CELL_WIDTH = 7
    private const val CELL_HEIGHT = 8
    private const val WHITE = 0xFFFFFFFF.toInt()

    val icons: List<Pair<Int, List<String>>> =
        listOf(
            TabFont.VOICE_ON to
                listOf("0001000", "0011010", "1111001", "1111001", "1111001", "0011010", "0001000"),
            TabFont.VOICE_OFF to
                listOf("0001000", "0011000", "1111101", "1111010", "1111101", "0011000", "0001000"),
            TabFont.PARTY to
                listOf("0110110", "0110110", "0000000", "1111111", "1111111", "1111111", "0000000"),
        )

    fun provider(): String {
        val chars = icons.joinToString("") { "\\u%04X".format(it.first) }
        return """{"type":"bitmap","file":"grounds:font/tab_icons.png","ascent":7,"height":$CELL_HEIGHT,"chars":["$chars"]}"""
    }

    fun texture(): ByteArray {
        val image =
            java.awt.image.BufferedImage(
                icons.size * CELL_WIDTH,
                CELL_HEIGHT,
                java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
        icons.forEachIndexed { index, (_, pattern) ->
            pattern.forEachIndexed { row, pixels ->
                pixels.forEachIndexed { column, pixel ->
                    // One empty row on top keeps the 7 drawn rows level with the badge glyphs.
                    if (pixel == '1') image.setRGB(index * CELL_WIDTH + column, row + 1, WHITE)
                }
            }
        }
        return java.io.ByteArrayOutputStream().use { output ->
            check(javax.imageio.ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }
}

/** Compact uppercase tab labels drawn directly into a Minecraft bitmap-font atlas. */
internal object TabLabelsFont {
    private const val CELL_WIDTH = 6
    private const val CELL_HEIGHT = 7
    private const val COLUMNS = 16
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val glyphs = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789! .:-+_?/" + Char(0) + Char(0)

    private val patterns =
        mapOf(
            'A' to listOf("01110", "10001", "11111", "10001", "10001"),
            'B' to listOf("11110", "10001", "11110", "10001", "11110"),
            'C' to listOf("01111", "10000", "10000", "10000", "01111"),
            'D' to listOf("11110", "10001", "10001", "10001", "11110"),
            'E' to listOf("11111", "10000", "11110", "10000", "11111"),
            'F' to listOf("11111", "10000", "11110", "10000", "10000"),
            'G' to listOf("01111", "10000", "10111", "10001", "01111"),
            'H' to listOf("10001", "10001", "11111", "10001", "10001"),
            'I' to listOf("111", "010", "010", "010", "111"),
            'J' to listOf("00111", "00010", "00010", "10010", "01100"),
            'K' to listOf("10001", "10010", "11100", "10010", "10001"),
            'L' to listOf("10000", "10000", "10000", "10000", "11111"),
            'M' to listOf("10001", "11011", "10101", "10001", "10001"),
            'N' to listOf("10001", "11001", "10101", "10011", "10001"),
            'O' to listOf("01110", "10001", "10001", "10001", "01110"),
            'P' to listOf("11110", "10001", "11110", "10000", "10000"),
            'Q' to listOf("01110", "10001", "10101", "10010", "01101"),
            'R' to listOf("11110", "10001", "11110", "10010", "10001"),
            'S' to listOf("01111", "10000", "01110", "00001", "11110"),
            'T' to listOf("11111", "00100", "00100", "00100", "00100"),
            'U' to listOf("10001", "10001", "10001", "10001", "01110"),
            'V' to listOf("10001", "10001", "10001", "01010", "00100"),
            'W' to listOf("10001", "10001", "10101", "11011", "10001"),
            'X' to listOf("10001", "01010", "00100", "01010", "10001"),
            'Y' to listOf("10001", "01010", "00100", "00100", "00100"),
            'Z' to listOf("11111", "00010", "00100", "01000", "11111"),
            '0' to listOf("01110", "10011", "10101", "11001", "01110"),
            '1' to listOf("00100", "01100", "00100", "00100", "00111"),
            '2' to listOf("01110", "10001", "00010", "00100", "11111"),
            '3' to listOf("11110", "00001", "00110", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "11111", "00010"),
            '5' to listOf("11111", "10000", "11110", "00001", "11110"),
            '6' to listOf("01110", "10000", "11110", "10001", "01110"),
            '7' to listOf("11111", "00010", "00100", "01000", "01000"),
            '8' to listOf("01110", "10001", "01110", "10001", "01110"),
            '9' to listOf("01110", "10001", "01111", "00001", "01110"),
            '!' to listOf("1", "1", "1", "0", "1"),
            '.' to listOf("0", "0", "0", "0", "1"),
            ':' to listOf("0", "1", "0", "1", "0"),
            '-' to listOf("000", "000", "111", "000", "000"),
            '+' to listOf("00100", "00100", "11111", "00100", "00100"),
            '_' to listOf("00000", "00000", "00000", "00000", "11111"),
            '?' to listOf("01110", "10001", "00010", "00000", "00100"),
            '/' to listOf("00001", "00010", "00100", "01000", "10000"),
        )

    fun json(): String =
        """{"providers":[{"type":"space","advances":{" ":4}},{"type":"bitmap","file":"grounds:font/tab_labels.png","ascent":7,"height":7,"chars":[${glyphs.chunked(COLUMNS).joinToString(",") { "\"${it.replace("\u0000", "\\u0000")}\"" }}]},{"type":"reference","id":"minecraft:default"}]}"""

    fun texture(): ByteArray {
        val image =
            java.awt.image.BufferedImage(
                COLUMNS * CELL_WIDTH,
                21,
                java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
        glyphs.forEachIndexed { index, glyph ->
            val pattern = patterns[glyph] ?: return@forEachIndexed
            val originX = (index % COLUMNS) * CELL_WIDTH
            val originY = (index / COLUMNS) * CELL_HEIGHT + 1
            pattern.forEachIndexed { row, pixels ->
                pixels.forEachIndexed { column, pixel ->
                    if (pixel == '1') image.setRGB(originX + column, originY + row, WHITE)
                }
            }
        }
        return java.io.ByteArrayOutputStream().use { output ->
            check(javax.imageio.ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }
}
