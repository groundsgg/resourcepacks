package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackPath
import gg.grounds.resourcepack.api.VanillaPathClaim
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * `grounds:hud`: rounded pill backgrounds for text-only boss bar titles. Servers address these
 * codepoints directly, so they must not move.
 *
 * The pill is [HEIGHT] px tall with [ASCENT] ascent, so on a default-font line (glyphs from line y
 * to y + 7) it spans y - 2 to y + 9: 2 px above the cap height and 2 px below the descender. Every
 * column is white at ~75% alpha so the server tints it through the text colour (stone.deep
 * `#181D20`). Like any bitmap glyph, each pill glyph advances its ink width + 1 px.
 */
internal object HudFont {
    const val PILL_LEFT = 0xE200
    const val PILL_MIDDLE = 0xE201
    const val PILL_RIGHT = 0xE202
    const val SPACE_BASE = 0xE210
    const val HEIGHT = 12
    const val ASCENT = 9

    private const val FILL = 0xBFFFFFFF.toInt()
    private val STEPS = (0..10).map { 1 shl it }

    /** Pill glyph textures by file name; ink widths are 2, 1 and 2 px with 1 px corner cuts. */
    fun textures(): Map<String, ByteArray> =
        mapOf(
            "hud_pill_left.png" to pill(listOf(1 until HEIGHT - 1, 0 until HEIGHT)),
            "hud_pill_middle.png" to pill(listOf(0 until HEIGHT)),
            "hud_pill_right.png" to pill(listOf(0 until HEIGHT, 1 until HEIGHT - 1)),
        )

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
                    "hud_pill_left.png" to PILL_LEFT,
                    "hud_pill_middle.png" to PILL_MIDDLE,
                    "hud_pill_right.png" to PILL_RIGHT,
                )
                .map { (file, codepoint) ->
                    """{"type":"bitmap","file":"grounds:font/$file","ascent":$ASCENT,"height":$HEIGHT,"chars":["${u(codepoint)}"]}"""
                }
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},${bitmaps.joinToString(",")}]}"""
    }

    /** One filled row range per column. */
    private fun pill(columns: List<IntRange>): ByteArray {
        val image = BufferedImage(columns.size, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        columns.forEachIndexed { x, rows -> rows.forEach { y -> image.setRGB(x, y, FILL) } }
        return png(image)
    }

    private fun u(codepoint: Int): String = "\\u%04X".format(codepoint)
}

/**
 * Transparent replacements for the pink boss bar sprites, so a pink bar with the plain progress
 * overlay renders as its title only. Pink is reserved for HUD titles (library-minigame-api's
 * GroundsBossBar); every other colour and the notched overlays keep their vanilla look, because
 * games still draw real progress bars (Keystone uses white with notched_10).
 */
internal object HiddenBossBar {
    private const val WIDTH = 182
    private const val HEIGHT = 5

    val sprites: List<String> =
        listOf("pink_background", "pink_progress").map {
            "assets/minecraft/textures/gui/sprites/boss_bar/$it.png"
        }

    val vanillaClaims: Set<VanillaPathClaim> =
        sprites.map { VanillaPathClaim(PackPath.of(it)) }.toSet()

    fun entries(): List<PackEntry> {
        val transparent = png(BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB))
        return sprites.map { PackEntry.bytes(it, transparent) }
    }
}

private fun png(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
