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
                    "5542ae0511c18760a8dc055cce49f0740ef92cbf30cfdce97a08ff110daeb511",
                "assets/grounds/font/menu/title.json" to
                    "1be154bf46c2532cf33c58ac5f80e48c96380263403f6454e039233e5135620b",
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
                "assets/grounds/textures/font/menu/divider.png" to
                    "45cb340e1b031ede8d295e4f2ce4015ef7094ac28b7ef694d36d03c244cf8876",
                "assets/grounds/textures/font/menu/hit_1.png" to
                    "d5be3a3ebb8695ea5be637929f38f875fd07926d5b79006fa6bd9884e60c8e56",
                "assets/grounds/textures/font/menu/hit_128.png" to
                    "1f6753c42d4032a5ddc4cbd00eabad4227eba82f25515505877dab0658591030",
                "assets/grounds/textures/font/menu/hit_16.png" to
                    "88f145d2abf6b9397999173941f154e6b8e07df121ff0d27ef227c181a2153e9",
                "assets/grounds/textures/font/menu/hit_2.png" to
                    "469f4de97eb8a644185fa5798cfb77fd67810f5d387c30d62abce9595f6a8333",
                "assets/grounds/textures/font/menu/hit_256.png" to
                    "7b7e1383c5df8190b6207869dea266983ba783681ec98c82ae9263e990acb45c",
                "assets/grounds/textures/font/menu/hit_32.png" to
                    "1955ee969239908c59c5a2d94bb882b67d31fae7f19fd1620ae3e804cbb49e62",
                "assets/grounds/textures/font/menu/hit_4.png" to
                    "4c46856d212d19c456a007df3c5a7ff8d96c7de720ed924f33cec805db6c35bf",
                "assets/grounds/textures/font/menu/hit_64.png" to
                    "1d68d2246495c5d023d77627960f2bf1778bc6365c089aff93cf38b8526d7b5d",
                "assets/grounds/textures/font/menu/hit_8.png" to
                    "99d24af41f65a9b552ec106494c39d8252442eb556c7f5bbf538f7c57db9378e",
                "assets/grounds/textures/font/menu/settings_panel_2.png" to
                    "a626782a8f6e625d965224505dbe893016609261cab62d7f84c776c381efc126",
                "assets/minecraft/shaders/core/gui.fsh" to
                    "f98c3a1a289fcaa9efe8469668fe2975bcfd3de9c1636ac012b4c049c719f061",
                "assets/minecraft/shaders/core/gui.vsh" to
                    "e55cc589647da98176c38ed69db32a2a769bcf34175109e3ba854023b946f134",
            )
    }
}
