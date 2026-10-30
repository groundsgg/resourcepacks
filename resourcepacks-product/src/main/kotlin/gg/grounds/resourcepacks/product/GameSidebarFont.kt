package gg.grounds.resourcepacks.product

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** First-party eight-pixel symbols for the compact game information rows. */
internal object GameSidebarFont {
    private val pixels =
        linkedMapOf(
            "bed" to
                listOf(
                    "........",
                    "........",
                    "#.......",
                    "#.##....",
                    "########",
                    "########",
                    "#......#",
                    "#......#",
                ),
            "team" to
                listOf(
                    "#......#",
                    ".##..##.",
                    ".##..##.",
                    "........",
                    "###..###",
                    "########",
                    "########",
                    "........",
                ),
            "progress" to
                listOf(
                    ".......#",
                    "......##",
                    ".....###",
                    "....####",
                    "...#####",
                    "..######",
                    ".#######",
                    "########",
                ),
            "coins" to
                listOf(
                    "..####..",
                    ".######.",
                    "###..###",
                    "###.####",
                    "###.####",
                    "###..###",
                    ".######.",
                    "..####..",
                ),
            "map" to
                listOf(
                    "##....##",
                    "########",
                    "#..#...#",
                    "#..#.#.#",
                    "#..#.#.#",
                    "#....#.#",
                    "########",
                    "##....##",
                ),
            "players" to
                listOf(
                    "#......#",
                    ".##..##.",
                    ".##..##.",
                    "........",
                    ".##..##.",
                    "########",
                    "########",
                    "........",
                ),
            "swords" to
                listOf(
                    "##....##",
                    ".##..##.",
                    "..####..",
                    "...##...",
                    "..####..",
                    ".##..##.",
                    "##....##",
                    "#......#",
                ),
            "trophy" to
                listOf(
                    "########",
                    "########",
                    "##.##.##",
                    ".######.",
                    "..####..",
                    "...##...",
                    "..####..",
                    ".######.",
                ),
        )

    fun json(): String =
        """{"providers":[${pixels.keys.mapIndexed { index, name ->
        """{"type":"bitmap","file":"grounds:font/game_sidebar_$name.png","height":8,"ascent":7,"chars":["\u%04X"]}""".format(0xE340 + index)
    }.joinToString(",")}]}"""

    fun textures(): Map<String, ByteArray> =
        pixels
            .map { (name, rows) ->
                require(rows.size == 8 && rows.all { it.length == 8 })
                val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
                rows.forEachIndexed { y, row ->
                    row.forEachIndexed { x, value ->
                        if (value == '#')
                            repeat(3) { dy ->
                                repeat(3) { dx -> image.setRGB(x * 3 + dx, y * 3 + dy, -1) }
                            }
                    }
                }
                "game_sidebar_$name.png" to
                    ByteArrayOutputStream().use { out ->
                        check(ImageIO.write(image, "png", out))
                        out.toByteArray()
                    }
            }
            .toMap()
}
