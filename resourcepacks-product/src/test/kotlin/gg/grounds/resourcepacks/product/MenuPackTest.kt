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
                    "f05ea648eecb81da20386febcf400bb017b98679c9ca0b4866070496c48f067b",
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
                "assets/grounds/textures/font/menu/nav_panel_3.png" to
                    "4981898f5704406e607dc6a0571a282bd9b4db7e17aecd127df94994519bd212",
                "assets/grounds/textures/font/menu/tabs_content_3_l.png" to
                    "b2c7c94cca510d184ef7488b1411a2e1bff1935e2134c17bfea04ec36ea42f7c",
                "assets/grounds/textures/font/menu/tabs_content_3_r.png" to
                    "9c07bf8537e3a4db84a765aaaee231c9554e1a8218b198e713f6d9611496c897",
                "assets/grounds/textures/font/menu/map_sea3_l.png" to
                    "fe233ff66f5f3b0bf42c9019fbbf2a99b909d1d4eee4d347fed082ab79efdb50",
                "assets/grounds/textures/font/menu/map_sea3_r.png" to
                    "499a29a22e97059e84f6e0489fabc779f9071e35788b6628e4f5aaebfc2a7845",
                "assets/grounds/textures/font/menu/map3_na.png" to
                    "b017c1918000e3e92e4de34959634e69f99bc2ff3efdcafddd75e4cd49f31e01",
                "assets/grounds/textures/font/menu/map3_sa.png" to
                    "7ea0125a44adea449f3ef461ff63a815598cde0522452cf88f58cb27e17fdb5a",
                "assets/grounds/textures/font/menu/map3_eu.png" to
                    "84e76f79a43b052e571fc7bb73d481516ab1bc91ccba807483fe1609e97d47c8",
                "assets/grounds/textures/font/menu/map3_af.png" to
                    "13f87ebdc4add333df7cbeab960a2626d09494ffb6274a1ab936637aaf00cbcf",
                "assets/grounds/textures/font/menu/map3_as.png" to
                    "29f0e45d7e2d8a985fddf04fe599adb14c7a634c8ec48bd6c387e0ff6e6b80f1",
                "assets/grounds/textures/font/menu/map3_oc.png" to
                    "630c54660975e1c1c71e9e4974a7017cc5a288eae046fd2921cd44eb64943717",
                "assets/grounds/textures/font/menu/map3_na_picked.png" to
                    "95a276de1f225a15bfb976b417a8a5f59a190d8bfa3f6d0c894232d1eebfc155",
                "assets/grounds/textures/font/menu/map3_eu_picked.png" to
                    "2f87894daf284022d26505a0da7827d1270bc0e870c1cb2b5fbe36cf4a719763",
                "assets/grounds/textures/font/menu/map3_as_picked.png" to
                    "f927c2e44a1f9276626cbf8a5ce988527956881033a6da0498ad918a9aeb82aa",
                "assets/grounds/textures/font/menu/map3_oc_picked.png" to
                    "69862c37ce001e16be508105844eb5d320e051047962d7c313501e7f8b1ae1ac",
                "assets/grounds/textures/font/menu/row_tall_l.png" to
                    "7d779a3459663c93cf93f2af83fcec6eb094dc3e31d10b261e18fbcbcc924bc4",
                "assets/grounds/textures/font/menu/row_tall_r.png" to
                    "8d359d9787a583b1e732a6e5296c264d439eec38f74ad9d037135eec1f102a70",
                "assets/grounds/textures/font/menu/icon_globe.png" to
                    "b7cd1a03f046ffba54f00909c71534c533516aa9fb465babba69dc6aa89fbabd",
                "assets/grounds/textures/font/menu/icon_signal.png" to
                    "1eb466558c2cd2b0ee51929abd6160f0ca63f64c6b52ed633af23f0abbcef0db",
                "assets/grounds/textures/font/menu/icon_hand.png" to
                    "6c62fd61bffd86905c3495904c37241b98bcc0ee36dd77c92ff7627b1e3f39ad",
                "assets/grounds/textures/font/menu/pill_l.png" to
                    "b225fc1a1c44bf1d2687c7a954fad81a9dd9107aff3c5a6e0851adcc970a1593",
                "assets/grounds/textures/font/menu/pill_r.png" to
                    "2c73d3a21794734158c4ff5bf7d53cf7230102e6de115f044fea3b53295e08d6",
                "assets/grounds/textures/font/menu/pill_1.png" to
                    "9e7cf515077d8847d485236cdd76d988625e74da8ddcbba550ecee5fefc56385",
                "assets/grounds/textures/font/menu/pill_2.png" to
                    "769d4f55ecd3d727caa57581648aaba0f388ece3eba7ee80a818e06dda4a8d07",
                "assets/grounds/textures/font/menu/pill_4.png" to
                    "775b2ddb642312a3900cd9fa58a8a80c639006f4b5297e2cc5f141158d0e03a8",
                "assets/grounds/textures/font/menu/pill_8.png" to
                    "1f318131d3147c4b72e32dad477582561a3927c129d8b86d056a60ea79271c97",
                "assets/grounds/textures/font/menu/pill_16.png" to
                    "51c1a79c13d1ce9c1282bf206515042bb3c471b195d8c10889430bca093ce164",
                "assets/grounds/textures/font/menu/pill_32.png" to
                    "711db4d2b1803ecfc0d7898bf4afdbffb761f6d9fa3cc58d6617ab427862a765",
                "assets/grounds/textures/font/menu/pill_64.png" to
                    "2f28036b823590780689ee8e49562ab01f4d2d33691c3d2cc3b8c3bf8a4b494b",
                "assets/grounds/textures/font/menu/pill_128.png" to
                    "b666e26fa1b77e86debda3a01c7c260def8a0e96b883aea64b60e96fa638912c",
                "assets/grounds/textures/font/menu/card_na.png" to
                    "5dd184f865e9c70d6bd0ea62219c40a29e1420408e6d8e989f7bdaed79dcd458",
                "assets/grounds/textures/font/menu/card_sa.png" to
                    "bc793640443b0ec55f4ab1f765e832c44119cd6035204e4c0998d0c9a4e37446",
                "assets/grounds/textures/font/menu/card_eu.png" to
                    "ec8e5842ca124705b37fd3058554b7a6a36d4c94247df988e8734b36c80016b2",
                "assets/grounds/textures/font/menu/card_af.png" to
                    "7ca0969dfe73de4fe80aa7f3d59bd86566ec0f330eb51568adc2d2df0ccf831f",
                "assets/grounds/textures/font/menu/card_as.png" to
                    "0d52a979191e3224955cafb33cc26122922d239b0fd0f2ccca14ddfa030241b9",
                "assets/grounds/textures/font/menu/card_oc.png" to
                    "850978003e42e0566872dbf6ef2f7c0999b9950c18c15a23f40eb03f3f2b6b54",
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
                    "1506b3752a3cfb99e02cf3c6c5e349d5dca70e09d2ea97fe8db1eca3f201522f",
                "assets/minecraft/shaders/core/gui.vsh" to
                    "be1cacd587abd7e2bb3dccefc9fbdb484cc83300079958bffbd0215bd05086c8",
            )
    }
}
