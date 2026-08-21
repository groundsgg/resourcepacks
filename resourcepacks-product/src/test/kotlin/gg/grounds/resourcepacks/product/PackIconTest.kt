package gg.grounds.resourcepacks.product

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackIconTest {
    @Test
    fun `approved held artwork is the exact visible square platform pack icon and ZIP entry`() {
        val parent = Files.createTempDirectory("pack-icon-")
        val scratch = SecureOwnedDirectory.create(parent, ".pack-icon-scratch-")
        try {
            ProductGraph.secureReleaseInputs().use { secured ->
                val platform = secured.packs.single { it.role == PackRole.PLATFORM }
                val icon = assertNotNull(platform.definition.icon)
                val bytes = icon.openStream().use { it.readAllBytes() }

                assertContentEquals(PNG_SIGNATURE, bytes.copyOf(PNG_SIGNATURE.size))
                assertEquals(APPROVED_ICON_SHA256, sha256(bytes))
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
                assertEquals(image.width, image.height)
                assertTrue(image.width > 0)
                assertTrue(image.hasVisiblePixel(), "pack icon must not be fully transparent")

                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val platformZip =
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.PLATFORM }.scratchFile
                    )
                val zipIcon =
                    ZipFile(platformZip.toFile()).use { zip ->
                        zip.getInputStream(assertNotNull(zip.getEntry("pack.png"))).readAllBytes()
                    }
                assertContentEquals(bytes, zipIcon)
                assertEquals(APPROVED_ICON_SHA256, sha256(zipIcon))
            }
        } finally {
            scratch.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun BufferedImage.hasVisiblePixel(): Boolean =
        (0 until height).any { y -> (0 until width).any { x -> (getRGB(x, y) ushr 24) != 0 } }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        const val APPROVED_ICON_SHA256 =
            "319a3bacb127ab5047b0373f86dff54934140b97192fb9d5552209be3fea210a"
    }
}
