package gg.grounds.resourcepacks.catalog

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContactSheetTest {
    @Test
    fun `renders grounds theme contact sheets for light and dark visual review`() {
        val root =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val art = root.resolve("art/platform")
        val review = root.resolve("build/visual-review")
        Files.createDirectories(review)

        listOf("light" to Color(0xeeeeee), "dark" to Color(0x202020)).forEach { (name, background)
            ->
            val sheet = BufferedImage(420, 250, BufferedImage.TYPE_INT_ARGB)
            val graphics = sheet.createGraphics()
            graphics.color = background
            graphics.fillRect(0, 0, sheet.width, sheet.height)
            draw(graphics, art.resolve("panels/menu.png"), 8, 8)
            draw(graphics, art.resolve("tooltips/default_bg.png"), 192, 8)
            draw(graphics, art.resolve("tooltips/default_frame.png"), 220, 8)
            draw(graphics, art.resolve("frames/hover.png"), 250, 8)
            listOf("close", "back", "next", "blank").forEachIndexed { index, id ->
                draw(graphics, art.resolve("icons/$id.png"), 192 + index * 28, 90)
            }
            graphics.dispose()
            val output = review.resolve("grounds-theme-$name.png").toFile()
            assertTrue(ImageIO.write(sheet, "png", output), "No PNG writer is available")
            assertTrue(output.isFile && output.length() > 0, "Contact sheet was not written")
        }
    }

    private fun draw(graphics: Graphics2D, path: Path, x: Int, y: Int) {
        graphics.drawImage(requireNotNull(ImageIO.read(path.toFile())), x, y, null)
    }
}
