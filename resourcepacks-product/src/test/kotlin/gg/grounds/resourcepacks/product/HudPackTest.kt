package gg.grounds.resourcepacks.product

import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HudPackTest {
    @Test
    fun `platform zip contains the hud font and transparent pink boss bar sprites`() {
        ProductGraph.secureReleaseInputs().use { secured ->
            val parent = java.nio.file.Files.createTempDirectory("hud-pack-")
            val scratch = SecureOwnedDirectory.create(parent, ".hud-pack-")
            try {
                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val zipPath =
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.PLATFORM }.scratchFile
                    )
                ZipFile(zipPath.toFile()).use { zip ->
                    val font =
                        zip.getInputStream(zip.getEntry("assets/grounds/font/hud.json"))
                            .reader()
                            .readText()
                    assertEquals(EXPECTED_FONT, font)

                    PILLS.forEach { (name, columns) ->
                        val image =
                            zip.getInputStream(
                                    assertNotNull(
                                        zip.getEntry("assets/grounds/textures/font/$name"),
                                        name,
                                    )
                                )
                                .use(ImageIO::read)
                        assertEquals(columns.size, image.width, name)
                        assertEquals(12, image.height, name)
                        columns.forEachIndexed { x, filled ->
                            (0 until 12).forEach { y ->
                                val expected = if (y in filled) 0xBFFFFFFF.toInt() else 0
                                assertEquals(expected, image.getRGB(x, y), "$name $x,$y")
                            }
                        }
                    }

                    assertEquals(2, BOSS_BAR_SPRITES.size)
                    BOSS_BAR_SPRITES.forEach { sprite ->
                        val path = "assets/minecraft/textures/gui/sprites/boss_bar/$sprite.png"
                        val image =
                            zip.getInputStream(assertNotNull(zip.getEntry(path), path))
                                .use(ImageIO::read)
                        assertEquals(182, image.width, path)
                        assertEquals(5, image.height, path)
                        assertTrue(
                            (0 until 182).all { x ->
                                (0 until 5).all { y -> image.getRGB(x, y) ushr 24 == 0 }
                            },
                            path,
                        )
                    }
                }
            } finally {
                scratch.close()
                parent.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `platform claims only the pink boss bar sprites`() {
        val claims =
            ProductGraph.packs
                .last()
                .contributions
                .single()
                .vanillaClaims
                .map { it.path.toString() }
                .filter { it.contains("/boss_bar/") }
                .toSet()

        assertEquals(
            BOSS_BAR_SPRITES.map { "assets/minecraft/textures/gui/sprites/boss_bar/$it.png" }
                .toSet(),
            claims,
        )
    }

    private companion object {
        val EXPECTED_FONT =
            "{\"providers\":[{\"type\":\"space\",\"advances\":{" +
                "\"\\uE210\":1,\"\\uE211\":-1,\"\\uE212\":2,\"\\uE213\":-2," +
                "\"\\uE214\":4,\"\\uE215\":-4,\"\\uE216\":8,\"\\uE217\":-8," +
                "\"\\uE218\":16,\"\\uE219\":-16,\"\\uE21A\":32,\"\\uE21B\":-32," +
                "\"\\uE21C\":64,\"\\uE21D\":-64,\"\\uE21E\":128,\"\\uE21F\":-128," +
                "\"\\uE220\":256,\"\\uE221\":-256,\"\\uE222\":512,\"\\uE223\":-512," +
                "\"\\uE224\":1024,\"\\uE225\":-1024}}," +
                "{\"type\":\"bitmap\",\"file\":\"grounds:font/hud_pill_left.png\",\"ascent\":9,\"height\":12,\"chars\":[\"\\uE200\"]}," +
                "{\"type\":\"bitmap\",\"file\":\"grounds:font/hud_pill_middle.png\",\"ascent\":9,\"height\":12,\"chars\":[\"\\uE201\"]}," +
                "{\"type\":\"bitmap\",\"file\":\"grounds:font/hud_pill_right.png\",\"ascent\":9,\"height\":12,\"chars\":[\"\\uE202\"]}]}"

        /** Filled rows per column: rounded caps cut one pixel off each outer corner. */
        val PILLS =
            mapOf(
                "hud_pill_left.png" to listOf(1..10, 0..11),
                "hud_pill_middle.png" to listOf(0..11),
                "hud_pill_right.png" to listOf(0..11, 1..10),
            )

        val BOSS_BAR_SPRITES = listOf("pink_background", "pink_progress")
    }
}
