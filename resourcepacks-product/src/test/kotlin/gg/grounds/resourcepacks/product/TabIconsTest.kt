package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabIconsTest {
    @Test
    fun `the tab font maps the voice and party icons onto the icon atlas`() {
        val json = TabFont.json()
        val provider =
            "{\"type\":\"bitmap\",\"file\":\"grounds:font/tab_icons.png\",\"ascent\":7," +
                "\"height\":8,\"chars\":[\"\\uE005\\uE006\\uE007\"]}"
        assertTrue(json.contains(provider), json)
    }

    @Test
    fun `each icon is drawn white in its own 7 by 8 cell below an empty top row`() {
        val image = ImageIO.read(ByteArrayInputStream(TabIcons.texture()))
        assertEquals(TabIcons.icons.size * 7, image.width)
        assertEquals(8, image.height)
        TabIcons.icons.forEachIndexed { index, (_, pattern) ->
            (0 until 7).forEach { x -> assertEquals(0, image.getRGB(index * 7 + x, 0)) }
            pattern.forEachIndexed { row, pixels ->
                pixels.forEachIndexed { column, pixel ->
                    val expected = if (pixel == '1') 0xFFFFFFFF.toInt() else 0
                    assertEquals(
                        expected,
                        image.getRGB(index * 7 + column, row + 1),
                        "$index $column,$row",
                    )
                }
            }
        }
    }
}
