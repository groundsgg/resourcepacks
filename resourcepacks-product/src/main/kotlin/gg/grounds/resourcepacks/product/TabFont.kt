package gg.grounds.resourcepacks.product

internal object TabFont {
    const val LOGO = 0xE000
    const val BADGE_LEFT = 0xE001
    const val BADGE_MIDDLE = 0xE002
    const val BADGE_RIGHT = 0xE003
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
                bitmap("tab_logo.png", ascent = 26, height = 32, LOGO),
                bitmap("tab_badge_left.png", ascent = 7, height = 8, BADGE_LEFT),
                bitmap("tab_badge_middle.png", ascent = 7, height = 8, BADGE_MIDDLE),
                bitmap("tab_badge_right.png", ascent = 7, height = 8, BADGE_RIGHT),
            )
        return """{"providers":[{"type":"space","advances":{${advances.joinToString(",")}}},${bitmaps.joinToString(",")}]}"""
    }

    private fun bitmap(file: String, ascent: Int, height: Int, codepoint: Int): String =
        """{"type":"bitmap","file":"grounds:font/$file","ascent":$ascent,"height":$height,"chars":["${u(codepoint)}"]}"""

    private fun u(codepoint: Int): String = "\\u%04X".format(codepoint)
}
