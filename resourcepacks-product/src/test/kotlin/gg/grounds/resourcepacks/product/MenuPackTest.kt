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
                    "89c5852f07a4a8d6609e0843b1a9aa577cbd557cbd4fa72a45223589cf6ba3cd",
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
                "assets/grounds/textures/font/menu/nav_panel_2.png" to
                    "eb6c40c763b40c424fb382a13b88b97d630d77d2188de271f4cae36a212872ab",
                "assets/grounds/textures/font/menu/row_wide_l.png" to
                    "bb98a7bbd49a9fbbfba0cef18e13fff08fa61799eb500e460d6f8a8e72ef7c5a",
                "assets/grounds/textures/font/menu/row_wide_r.png" to
                    "78dd9707f4a9a3bcfb7815bdff4bd7484f12dee1ba63e2bd65c6eb6269db123f",
                "assets/grounds/textures/font/menu/region_panel_l.png" to
                    "f5dc182d9d049992c5a87401dac73a4078f8dd3d9f09d6ec4f7349bb24052121",
                "assets/grounds/textures/font/menu/region_panel_r.png" to
                    "2b71987127d825fc8a0fec62ff993a057c2b6ee429a3a9bd9c8fb2fa506b8673",
                "assets/grounds/textures/font/menu/map_sea_l.png" to
                    "fcb9ffb65e4332d9b688f72389424738f5a95bb398445b7dc9e7fd639d0b6a53",
                "assets/grounds/textures/font/menu/map_sea_r.png" to
                    "f9d3ad43c342e60c0b7496bfbb8ae757a41983fbcaeab1444fb38f8a4393576d",
                "assets/grounds/textures/font/menu/map2_na.png" to
                    "b197322ee1463745b5b5e55c36c68e35d4e3a0b0ec827da4dec2211d804d91e7",
                "assets/grounds/textures/font/menu/map2_sa.png" to
                    "fa11f6c37e22857e78e93773b9b349b73ea7425b569c47300381a7525b6737e2",
                "assets/grounds/textures/font/menu/map2_eu.png" to
                    "eceef338db9228c97f82dc0a676038915e5105deae94b8f10df09199ee778d3e",
                "assets/grounds/textures/font/menu/map2_af.png" to
                    "09ae3b8c03084041f8943c41ccd173e466bde56121291ac521e0c530344da3ef",
                "assets/grounds/textures/font/menu/map2_as.png" to
                    "d48aff6683609f93b2145bde2cb2e46773d318575bb0844a8073bcf4917e4376",
                "assets/grounds/textures/font/menu/map2_oc.png" to
                    "2b706d442983b972d6a9d333611edcd16f8c3574235ffc58236a15530f544d3c",
                "assets/grounds/textures/font/menu/map2_na_picked.png" to
                    "3eff6b9f109ee4897c238450349af2b11bc05949db4ddbf7df5ab5839be5b183",
                "assets/grounds/textures/font/menu/map2_eu_picked.png" to
                    "208c9b87d27a4626d1fa8c5e5d202fb53a1b99feba278a062f9adf5dcb361fc5",
                "assets/grounds/textures/font/menu/map2_as_picked.png" to
                    "2115b457c9e8e3f41dff55c00b4239a0bc9fd9cb48977940ed2ddfbc9131f5e9",
                "assets/grounds/textures/font/menu/map2_oc_picked.png" to
                    "60e4d1cc774e656c3b9e40aae357f20215d4f66a424ba1682ac1195ab411bc67",
                "assets/grounds/textures/font/menu/region_panel.png" to
                    "8e5825f5b397b92f7eaa1099104158d4b15bef043c32420a550b8a4728011675",
                "assets/grounds/textures/font/menu/map_sea.png" to
                    "38fb275453a1724d315966f2963bb6beefa6e425fd5ab2d44e1d4ea268de0cd5",
                "assets/grounds/textures/font/menu/map_na.png" to
                    "bf03a0f2418b5b966d262f6897a3f792eef2f6b4d9ff6f42bd5e35ae764baf6b",
                "assets/grounds/textures/font/menu/map_sa.png" to
                    "4f3b95637701e453eb2e05b334a00bfd2a1f17a131bdbeb4f5ef7f100307d8c5",
                "assets/grounds/textures/font/menu/map_eu.png" to
                    "b156b091199e938a8232364163c3c6c36fcffbbd488c84096c7e4e86ce55a74d",
                "assets/grounds/textures/font/menu/map_af.png" to
                    "d301eeb129615f9fb6ef3bd1be936cfa5f01a66d539a1cdc5498d41f379cd29a",
                "assets/grounds/textures/font/menu/map_as.png" to
                    "52b8935b10cab896175dc4e9b534410be4e1e593b37db9e100224e7fc79ac18b",
                "assets/grounds/textures/font/menu/map_oc.png" to
                    "69f44ab1be75c115573a93cc26d44ae649f2d139559e457c183bb6c75030ddeb",
                "assets/grounds/textures/font/menu/map_na_picked.png" to
                    "2f81d49dbd2e3dfddac83f618f68feed8860c2fcbfba6d43b5a06936923cf55b",
                "assets/grounds/textures/font/menu/map_eu_picked.png" to
                    "1568a685e3b4a02b628ce36e97bf8dc0cb0f8622b0a35bd92085dad8cb385ccd",
                "assets/grounds/textures/font/menu/map_as_picked.png" to
                    "00ef35622d34c5c8e1c4a283bf9b08466442d1c2ff5cb84a29cc815cc101b650",
                "assets/grounds/textures/font/menu/map_oc_picked.png" to
                    "05cc8de6a4392d796e58d9f9cf46f740ab521a7dc5b5c6851aba1ba55119faa2",
                "assets/grounds/textures/font/menu/map_zone.png" to
                    "1d2da3bbcfc5868db44a3b8016075fba0158c0b2fc1eb945a27bd3211171ac12",
                "assets/grounds/textures/font/menu/nav_panel.png" to
                    "1a233e1ec812f194a0bbe3d951697a394557d7146d81e75c3cfa60985686ca47",
                "assets/grounds/textures/font/menu/tabs_content_panel.png" to
                    "98e0568c2203b21a9e86cda8085b2c3be6767378a369d35b8a60aee1898ba983",
                "assets/grounds/textures/font/menu/nav_button.png" to
                    "5bac550c855ad2677ae9bac6f24926fa0305ec6170cbfe0852ec43918f777f42",
                "assets/grounds/textures/font/menu/nav_selected.png" to
                    "caa178905b842d568fc0a9b3d38918b3fe7a0ff40d28f9c637b845cc7c002b7d",
                "assets/minecraft/shaders/core/gui.fsh" to
                    "f98c3a1a289fcaa9efe8469668fe2975bcfd3de9c1636ac012b4c049c719f061",
                "assets/minecraft/shaders/core/gui.vsh" to
                    "e55cc589647da98176c38ed69db32a2a769bcf34175109e3ba854023b946f134",
            )
    }
}
