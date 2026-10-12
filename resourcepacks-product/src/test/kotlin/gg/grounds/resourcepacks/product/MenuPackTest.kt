package gg.grounds.resourcepacks.product

import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MenuPackTest {
    @Test
    fun `platform zip ships the plugin-lobby menu pack byte for byte`() {
        ProductGraph.secureReleaseInputs().use { secured ->
            val parent = java.nio.file.Files.createTempDirectory("menu-pack-")
            val scratch = SecureOwnedDirectory.create(parent, ".menu-pack-")
            try {
                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val zipPath =
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.PLATFORM }.scratchFile
                    )
                ZipFile(zipPath.toFile()).use { zip ->
                    MENU_DIGESTS.forEach { (path, digest) ->
                        val entry = assertNotNull(zip.getEntry(path), path)
                        val actual =
                            zip.getInputStream(entry).use { input ->
                                MessageDigest.getInstance("SHA-256")
                                    .digest(input.readBytes())
                                    .joinToString("") { "%02x".format(it) }
                            }
                        assertEquals(digest, actual, path)
                    }
                    assertNotNull(zip.getEntry("assets/minecraft/shaders/core/text.vsh"))
                }
            } finally {
                scratch.close()
                parent.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `platform claims both gui shaders next to the text shader`() {
        val claims =
            ProductGraph.packs
                .last()
                .contributions
                .single()
                .vanillaClaims
                .map { it.path.toString() }
                .toSet()

        assertEquals(
            setOf(
                "assets/minecraft/shaders/core/gui.fsh",
                "assets/minecraft/shaders/core/gui.vsh",
                "assets/minecraft/shaders/core/text.vsh",
            ),
            claims.filter { it.startsWith("assets/minecraft/shaders/") }.toSet(),
        )
    }

    private companion object {
        /** `SHA256SUMS` of plugin-lobby's `MenuPackTest` output. */
        val MENU_DIGESTS =
            mapOf(
                "assets/grounds/font/menu/art.json" to
                    "fc23289a3e30fc29638b9d4ac39093fe47bcfcde124de52308a50c8752aa6266",
                "assets/grounds/font/menu/space.json" to
                    "376ee38fb6e43bd2f193d221272ea5b2235018b1eff0a45c48c5db4e815d1d7a",
                "assets/grounds/font/menu/text_0.json" to
                    "513a9830d3e501c2df942d6812216dbb6c98b2297f51ff84e599081e733b1e26",
                "assets/grounds/font/menu/text_1.json" to
                    "fed55fd6a6c19c0327556d4923ba39bd4b604cddf060e39dad16e0a1de868cc8",
                "assets/grounds/font/menu/text_2.json" to
                    "c613e2b95dadce54d5d1037f06f999819902a5eda75430a8e2842774d515744a",
                "assets/grounds/font/menu/text_3.json" to
                    "16c3ca5afca1e73974a53cfbb8d9d040ebee4ce1de97b6eca18e6b83be860ba2",
                "assets/grounds/font/menu/text_4.json" to
                    "bab2de90d115a12c913b84e77989ba3f0bfe1430c3f92ed8a9157fd1cee291ea",
                "assets/grounds/font/menu/text_5.json" to
                    "fb047286617434ecac5fb3a54086249f615c2ffb9b26cf9daaa7fb9411af7012",
                "assets/grounds/font/menu/text_6.json" to
                    "c0580a064621dd288d5fd4ed34dfa428d9038f5fa9291c47b493b7f121233bb5",
                "assets/grounds/font/menu/text_7.json" to
                    "992a2c3c8aafc27c6a478df5bfd453bcfd99b4aa8d81f2567e46da0d94a6e1d6",
                "assets/grounds/font/menu/text_8.json" to
                    "ef68e198654f53d962325a192e4aa4b4d3c57051e5bb60a5f7189c11a428c767",
                "assets/grounds/textures/font/menu/arrow_left.png" to
                    "197849612738a0ce2f9e96f665ac22c0a757b22678f359f948d7f9e53c068a97",
                "assets/grounds/textures/font/menu/arrow_right.png" to
                    "e89e9396dbd5d40192dd9e385f3d30f7357cb9932d829ec53ae84ae332389415",
                "assets/grounds/textures/font/menu/panel.png" to
                    "0cd2c7f7a70a53701b6ce5e75ec6efe8fed7209701a5ffecd0a4363ba9f545db",
                "assets/grounds/textures/font/menu/settings_panel.png" to
                    "98e0568c2203b21a9e86cda8085b2c3be6767378a369d35b8a60aee1898ba983",
                "assets/grounds/textures/font/menu/row.png" to
                    "e25804d9002924bd2bd1cdfdbb09993c098cfb769540af4bce5aab1effdabdd9",
                "assets/grounds/textures/font/menu/toggle_off.png" to
                    "ede564a42b07d0670bdc3a29086cce9a436098cb0df39729c4c1163e292f5fab",
                "assets/grounds/textures/font/menu/toggle_on.png" to
                    "00874ff90dd9484221fdf5fc739d50d76a6b7cb8eb075637d3ae644cdea0006c",
                "assets/minecraft/shaders/core/gui.fsh" to
                    "f98c3a1a289fcaa9efe8469668fe2975bcfd3de9c1636ac012b4c049c719f061",
                "assets/minecraft/shaders/core/gui.vsh" to
                    "e55cc589647da98176c38ed69db32a2a769bcf34175109e3ba854023b946f134",
            )
    }
}
