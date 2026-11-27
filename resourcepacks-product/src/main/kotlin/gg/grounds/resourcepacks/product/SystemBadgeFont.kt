package gg.grounds.resourcepacks.product

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The atlas order is shared with library-i18n's SystemBadgeGlyphs. */
internal object SystemBadgeFont {
    private const val WIDTH = 144
    private const val HEIGHT = 16
    private const val CATEGORIES = 5
    private const val FIRST_GLYPH = 0xE000
    private const val BACKGROUND = 0x303641
    private const val LABEL = 0xF4F1E9

    private val labels =
        listOf(
            listOf("GROUNDS", "FRIENDS", "PARTY", "MATCH", "STORY"),
            listOf("GROUNDS", "FREUNDE", "PARTY", "MATCH", "STORY"),
            listOf("GROUNDS", "AMIGOS", "GRUPO", "PARTIDA", "HISTORIA"),
            listOf("GROUNDS", "AMIS", "GROUPE", "PARTIE", "HISTOIRE"),
            listOf("GROUNDS", "ARKADAŞLAR", "GRUP", "MAÇ", "HİKÂYE"),
        )

    fun json(): String {
        val chars =
            labels.indices.joinToString(",") { row ->
                (0 until CATEGORIES).joinToString("", prefix = "\"", postfix = "\"") { column ->
                    "\\u%04X".format(FIRST_GLYPH + row * CATEGORIES + column)
                }
            }
        return """{"providers":[{"type":"space","advances":{"\uE019":3}},{"type":"bitmap","file":"grounds:font/chat/system_badges.png","ascent":7,"height":8,"chars":[$chars]}]}"""
    }

    fun texture(logo: ByteArray, sprites: List<ByteArray>): ByteArray {
        val icons =
            (listOf(logo) + sprites).map { bytes ->
                checkNotNull(ImageIO.read(bytes.inputStream())) { "Invalid system badge icon PNG" }
            }
        check(icons.size == CATEGORIES)
        check(icons.drop(1).all { it.width == HEIGHT && it.height == HEIGHT })

        val atlas =
            BufferedImage(WIDTH * CATEGORIES, HEIGHT * labels.size, BufferedImage.TYPE_INT_RGB)
        val graphics = atlas.createGraphics()
        try {
            graphics.color = Color(BACKGROUND)
            graphics.fillRect(0, 0, atlas.width, atlas.height)
            labels.forEachIndexed { row, translations ->
                translations.forEachIndexed { column, label ->
                    val x = column * WIDTH
                    val y = row * HEIGHT
                    graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        if (column == 0) RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                    )
                    graphics.drawImage(icons[column], x + 4, y + 1, 14, 14, null)
                    drawLabel(atlas, x, y, label)
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

    private fun drawLabel(atlas: BufferedImage, x: Int, y: Int, label: String) {
        var cursor = x + 22
        label.forEach { glyph ->
            val base =
                when (glyph) {
                    'Â' -> 'A'
                    'Ç' -> 'C'
                    'İ' -> 'I'
                    'Ş' -> 'S'
                    else -> glyph
                }
            val rows = TabLabelsFont.pattern(base)
            val width = rows.first().length
            check(cursor + width * 2 <= x + WIDTH - 4) { "System badge label is too wide: $label" }

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
            cursor += width * 2 + 2
        }
    }
}
