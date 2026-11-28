package gg.grounds.resourcepacks.product

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The atlas order is shared with library-i18n's SystemBadgeGlyphs. */
internal object SystemBadgeFont {
    private const val MAX_BADGE_WIDTH = 130
    private const val DOT_OFFSET = 10
    private const val DOT_SIZE = 2
    private const val CELL_WIDTH = MAX_BADGE_WIDTH + DOT_OFFSET + DOT_SIZE
    private const val HEIGHT = 16
    private const val CATEGORIES = 5
    private const val FIRST_GLYPH = 0xE000
    private const val LABEL_PADDING = 6
    private const val LETTER_SPACING = 2
    private const val LABEL = 0xFFF4F1E9.toInt()
    private val dot = Color(0xA3AAB5)
    private val backgrounds = listOf(0x564936, 0x156457, 0x57377B, 0x214D81, 0x734D1E).map(::Color)
    private val cornerInsets = intArrayOf(4, 2, 1)

    private val labels =
        listOf(
            listOf("SYSTEM", "FRIENDS", "PARTY", "MATCH", "STORY"),
            listOf("SYSTEM", "FREUNDE", "PARTY", "MATCH", "STORY"),
            listOf("SYSTEM", "AMIGOS", "GRUPO", "PARTIDA", "HISTORIA"),
            listOf("SYSTEM", "AMIS", "GROUPE", "PARTIE", "HISTOIRE"),
            listOf("SYSTEM", "ARKADAŞLAR", "GRUP", "MAÇ", "HİKÂYE"),
        )

    fun json(): String {
        val chars =
            labels.indices.joinToString(",") { row ->
                (0 until CATEGORIES).joinToString("", prefix = "\"", postfix = "\"") { column ->
                    "\\u%04X".format(FIRST_GLYPH + row * CATEGORIES + column)
                }
            }
        return """{"providers":[{"type":"space","advances":{"\uE019":4}},{"type":"bitmap","file":"grounds:font/chat/system_badges.png","ascent":7,"height":8,"chars":[$chars]}]}"""
    }

    fun texture(): ByteArray {
        val atlas =
            BufferedImage(
                CELL_WIDTH * CATEGORIES,
                HEIGHT * labels.size,
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = atlas.createGraphics()
        try {
            labels.forEachIndexed { row, translations ->
                translations.forEachIndexed { column, label ->
                    val x = column * CELL_WIDTH
                    val y = row * HEIGHT
                    val labelWidth =
                        label.sumOf { letterRows(it).first().length * 2 + LETTER_SPACING } -
                            LETTER_SPACING
                    val width = labelWidth + 2 * LABEL_PADDING
                    check(width <= MAX_BADGE_WIDTH) { "System badge label is too wide: $label" }

                    graphics.color = backgrounds[column]
                    graphics.fillRect(
                        x,
                        y + cornerInsets.size,
                        width,
                        HEIGHT - 2 * cornerInsets.size,
                    )
                    cornerInsets.forEachIndexed { edge, inset ->
                        graphics.fillRect(x + inset, y + edge, width - 2 * inset, 1)
                        graphics.fillRect(x + inset, y + HEIGHT - 1 - edge, width - 2 * inset, 1)
                    }
                    drawLabel(atlas, x, y, label)

                    // The separator sits five GUI pixels after the badge (glyph advance + one
                    // space).
                    // U+E019 supplies the trailing space so older clients of this font keep the
                    // same glyphs.
                    graphics.color = dot
                    graphics.fillRect(x + width + DOT_OFFSET, y + 6, DOT_SIZE, DOT_SIZE)
                }
            }
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(atlas, "png", output))
            output.toByteArray()
        }
    }

    private fun letterRows(glyph: Char): List<String> =
        TabLabelsFont.pattern(
            when (glyph) {
                'Â' -> 'A'
                'Ç' -> 'C'
                'İ' -> 'I'
                'Ş' -> 'S'
                else -> glyph
            }
        )

    private fun drawLabel(atlas: BufferedImage, x: Int, y: Int, label: String) {
        var cursor = x + LABEL_PADDING
        label.forEach { glyph ->
            val rows = letterRows(glyph)

            rows.forEachIndexed { row, bits ->
                bits.forEachIndexed { column, bit ->
                    if (bit == '1') {
                        val pixelX = cursor + column * 2
                        val pixelY = y + 3 + row * 2
                        atlas.setRGB(pixelX, pixelY, LABEL)
                        atlas.setRGB(pixelX + 1, pixelY, LABEL)
                        atlas.setRGB(pixelX, pixelY + 1, LABEL)
                        atlas.setRGB(pixelX + 1, pixelY + 1, LABEL)
                    }
                }
            }
            when (glyph) {
                'Â' -> {
                    atlas.setRGB(cursor + 4, y, LABEL)
                    atlas.setRGB(cursor + 3, y + 1, LABEL)
                    atlas.setRGB(cursor + 5, y + 1, LABEL)
                }
                'İ' -> {
                    atlas.setRGB(cursor + 2, y, LABEL)
                    atlas.setRGB(cursor + 3, y, LABEL)
                }
                'Ç',
                'Ş' -> {
                    atlas.setRGB(cursor + 5, y + 13, LABEL)
                    atlas.setRGB(cursor + 5, y + 14, LABEL)
                    atlas.setRGB(cursor + 4, y + 14, LABEL)
                }
            }
            cursor += rows.first().length * 2 + LETTER_SPACING
        }
    }
}
