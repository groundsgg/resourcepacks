package gg.grounds.resourcepacks.product

import gg.grounds.gui.pack.toPackContribution
import gg.grounds.resourcepack.api.ByteArrayEntrySource
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackDefinition
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackEntrySource
import gg.grounds.resourcepack.api.PackPolicy
import gg.grounds.resourcepack.api.VanillaPathPolicy
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.resourcepacks.catalog.GroundsGuiTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal enum class PackRole {
    CONTENT,
    PLATFORM,
}

internal data class PhysicalPack(
    val order: Int,
    val role: PackRole,
    val id: String,
    val uuid: UUID,
    val required: Boolean,
    val definition: PackDefinition,
    val contributions: List<PackContribution>,
)

internal object ProductGraph {
    val packs: List<PhysicalPack>
        get() = secureReleaseInputs().use { it.packs }

    /**
     * Opens every product artwork component before library-gui materialization, pins the exact
     * approved bytes, and keeps those descriptors alive until PackSet commit.
     */
    fun secureReleaseInputs(): SecureSourceInputs = secureReleaseInputs(platformArtDirectory()) {}

    @JvmSynthetic
    internal fun secureReleaseInputs(
        art: Path,
        afterSourcesPinned: () -> Unit,
    ): SecureSourceInputs {
        val held = linkedMapOf<String, HeldSourceFile>()
        try {
            PLATFORM_ARTWORK.forEach { (relative, expected) ->
                val source = HeldSourceFile.capture(art.resolve(relative), expected.size)
                if (
                    source.digests.size != expected.size || source.digests.sha256 != expected.sha256
                ) {
                    source.close()
                    throw java.io.IOException("Product artwork hash mismatch: $relative")
                }
                held[relative] = source
            }
            val packIcon =
                HeldSourceFile.capture(art.resolve(PACK_ICON_SOURCE), PACK_ICON_ARTWORK.size)
            if (
                packIcon.digests.size != PACK_ICON_ARTWORK.size ||
                    packIcon.digests.sha256 != PACK_ICON_ARTWORK.sha256
            ) {
                packIcon.close()
                throw java.io.IOException("Product artwork hash mismatch: $PACK_ICON_SOURCE")
            }
            held[PACK_ICON_SOURCE] = packIcon
            TAB_ARTWORK.forEach { (relative, expected) ->
                val source = HeldSourceFile.capture(art.resolve(relative), MAX_TAB_ARTWORK_BYTES)
                if (
                    source.digests.size != expected.size || source.digests.sha256 != expected.sha256
                ) {
                    source.close()
                    throw java.io.IOException("Product artwork hash mismatch: $relative")
                }
                held[relative] = source
            }
            CHAT_ARTWORK.forEach { (relative, expected) ->
                val source = HeldSourceFile.capture(art.resolve(relative), expected.size)
                if (
                    source.digests.size != expected.size || source.digests.sha256 != expected.sha256
                ) {
                    source.close()
                    throw java.io.IOException("Product artwork hash mismatch: $relative")
                }
                held[relative] = source
            }
            MENU_ARTWORK.forEach { (relative, expected) ->
                val source = HeldSourceFile.capture(art.resolve(relative), MAX_ARTWORK_BYTES)
                if (
                    source.digests.size != expected.size || source.digests.sha256 != expected.sha256
                ) {
                    source.close()
                    throw java.io.IOException("Product artwork hash mismatch: $relative")
                }
                held[relative] = source
            }
            held[PLATFORM_LICENSE_SOURCE] =
                HeldSourceFile.capture(art.resolve("LICENSE"), MAX_LICENSE_BYTES)
            held[CONTENT_LICENSE_SOURCE] =
                HeldSourceFile.capture(
                    contentArtDirectory(art).resolve("LICENSE"),
                    MAX_LICENSE_BYTES,
                )
            ContentContribution.modelSourcePaths.forEach { relative ->
                held[relative] =
                    HeldSourceFile.capture(
                        contentArtDirectory(art).resolve(relative),
                        MAX_CONTENT_MODEL_BYTES,
                    )
            }
            afterSourcesPinned()
            val contribution =
                immutableThemeContribution(held.filterKeys { it in PLATFORM_ARTWORK })
                    .withLicense(held.getValue(PLATFORM_LICENSE_SOURCE), PLATFORM_LICENSE_ENTRY)
                    .withTabArtwork(held)
                    .withChatFont(held)
                    .withHud()
                    .withMenu(held)
            val contentContribution =
                ContentContribution.withModels(held)
                    .withLicense(held.getValue(CONTENT_LICENSE_SOURCE), CONTENT_LICENSE_ENTRY)
            val icon =
                ByteArrayEntrySource(
                    held.getValue(PACK_ICON_SOURCE).readBytes(PACK_ICON_ARTWORK.size)
                )
            return SecureSourceInputs.captureWithHeld(
                    createPacks(contribution, contentContribution, icon),
                    held.values.toList(),
                )
                .also { it.verifyUnchanged() }
        } catch (failure: Throwable) {
            held.values.toList().asReversed().forEach { source ->
                try {
                    source.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
            }
            throw failure
        }
    }

    private fun createPacks(
        platformContribution: PackContribution,
        contentContribution: PackContribution,
        packIcon: PackEntrySource,
    ): List<PhysicalPack> =
        listOf(
            PhysicalPack(
                order = 0,
                role = PackRole.CONTENT,
                id = "grounds-content",
                uuid = PackSetConstants.contentUuid,
                required = true,
                definition =
                    PackDefinition(
                        packDescription("Content"),
                        PackSetConstants.packFormat,
                        null,
                        PackPolicy(VanillaPathPolicy.FORBID, PackSetConstants.contentLimits),
                    ),
                contributions = listOf(contentContribution),
            ),
            PhysicalPack(
                order = 1,
                role = PackRole.PLATFORM,
                id = "grounds-platform",
                uuid = PackSetConstants.platformUuid,
                required = true,
                definition =
                    PackDefinition(
                        packDescription("Platform"),
                        PackSetConstants.packFormat,
                        packIcon,
                        PackPolicy(VanillaPathPolicy.ALLOW_CLAIMED, PackSetConstants.platformLimits),
                    ),
                contributions = listOf(platformContribution),
            ),
        )

    private fun packDescription(role: String): String =
        "§6Grounds Network\n" + "§fPack » §e$role §7(${GroundsAssetCatalog.catalog.version})"

    /** Materializes library-gui from immutable bytes, never from mutable source paths. */
    private fun immutableThemeContribution(sources: Map<String, HeldSourceFile>): PackContribution {
        val immutableBytes =
            sources.mapValues { (relative, source) ->
                source.readBytes(PLATFORM_ARTWORK.getValue(relative).size)
            }
        return ImmutableSourceFileSystem.open(immutableBytes, MAX_ARTWORK_BYTES).use { fileSystem ->
            GroundsGuiTheme.theme.toPackContribution(fileSystem.root).withImmutableSources()
        }
    }

    private fun PackContribution.withLicense(
        source: HeldSourceFile,
        path: String,
    ): PackContribution =
        PackContribution(
            id,
            supportedFormats,
            entries + PackEntry.bytes(path, source.readBytes(source.digests.size)),
            vanillaClaims,
            provides,
            requires,
        )

    private fun PackContribution.withTabArtwork(
        held: Map<String, HeldSourceFile>
    ): PackContribution {
        val extra =
            TAB_PACK_PATHS.map { (relative, packPath) ->
                PackEntry.bytes(
                    packPath,
                    held.getValue(relative).readBytes(TAB_ARTWORK.getValue(relative).size),
                )
            } +
                listOf(
                    PackEntry.text("assets/grounds/font/tab.json", TabFont.json()),
                    PackEntry.text("assets/grounds/font/tab_labels.json", TabLabelsFont.json()),
                    PackEntry.bytes(
                        "assets/grounds/textures/font/tab_icons.png",
                        TabIcons.texture(),
                    ),
                    PackEntry.bytes(
                        "assets/grounds/textures/font/tab_labels.png",
                        TabLabelsFont.texture(),
                    ),
                )
        return PackContribution(
            id,
            supportedFormats,
            entries + extra,
            vanillaClaims,
            provides,
            requires,
        )
    }

    private fun PackContribution.withMenu(held: Map<String, HeldSourceFile>): PackContribution {
        val sprites =
            MENU_ARTWORK.map { (relative, expected) ->
                PackEntry.bytes(
                    "assets/grounds/textures/font/$relative",
                    held.getValue(relative).readBytes(expected.size),
                )
            }
        return PackContribution(
            id,
            supportedFormats,
            entries + sprites + MenuPack.entries(),
            vanillaClaims + MenuPack.vanillaClaims,
            provides,
            requires,
        )
    }

    private fun PackContribution.withChatFont(held: Map<String, HeldSourceFile>): PackContribution {
        val logo = CHAT_ARTWORK.getValue(CHAT_LOGO_SOURCE)
        val entries =
            listOf(
                PackEntry.text("assets/grounds/font/chat.json", ChatFont.json()),
                PackEntry.bytes(
                    "assets/grounds/textures/font/chat/logo.png",
                    held.getValue(CHAT_LOGO_SOURCE).readBytes(logo.size),
                ),
            ) +
                ChatFont.emojiTextures().map { (name, bytes) ->
                    PackEntry.bytes("assets/grounds/textures/font/chat/$name.png", bytes)
                }
        return PackContribution(
            id,
            supportedFormats,
            this.entries + entries,
            vanillaClaims,
            provides,
            requires,
        )
    }

    private fun PackContribution.withHud(): PackContribution =
        PackContribution(
            id,
            supportedFormats,
            entries +
                PackEntry.text("assets/grounds/font/hud.json", HudFont.json()) +
                HudFont.textures().map { (name, bytes) ->
                    PackEntry.bytes("assets/grounds/textures/font/$name", bytes)
                } +
                HiddenBossBar.entries(),
            vanillaClaims + HiddenBossBar.vanillaClaims,
            provides,
            requires,
        )

    private fun PackContribution.withImmutableSources(): PackContribution =
        PackContribution(
            id,
            supportedFormats,
            entries.map { entry -> PackEntry(entry.path, entry.source.immutableBytes()) },
            vanillaClaims,
            provides,
            requires,
        )

    private fun PackEntrySource.immutableBytes(): PackEntrySource =
        ByteArrayEntrySource(
            size().let { size ->
                if (size !in 0..MAX_MATERIALIZED_ARTWORK_BYTES) {
                    throw java.io.IOException("Materialized artwork exceeds its byte limit.")
                }
                val bytes = ByteArray(size.toInt())
                openStream().use { input ->
                    var cursor = 0
                    while (cursor < bytes.size) {
                        val read = input.read(bytes, cursor, bytes.size - cursor)
                        if (read < 0) {
                            throw java.io.IOException("Materialized artwork ended while reading.")
                        }
                        cursor += read
                    }
                    if (input.read() >= 0) {
                        throw java.io.IOException("Materialized artwork grew while reading.")
                    }
                }
                bytes
            }
        )

    private fun platformArtDirectory(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve("art/platform")

    private fun contentArtDirectory(platformArt: Path): Path = platformArt.parent.resolve("content")

    private data class ArtworkExpectation(val size: Long, val sha256: String)

    private const val MAX_ARTWORK_BYTES = 4L * 1024
    private const val MAX_TAB_ARTWORK_BYTES = 256L * 1024
    private const val MAX_MATERIALIZED_ARTWORK_BYTES = 1024L * 1024
    private const val MAX_LICENSE_BYTES = 16L * 1024
    private const val MAX_CONTENT_MODEL_BYTES = 16L * 1024
    private const val PACK_ICON_SOURCE = "pack.png"
    private const val CHAT_LOGO_SOURCE = "chat/logo.png"
    private const val CONTENT_LICENSE_ENTRY = "assets/grounds/legal/content.txt"
    private const val PLATFORM_LICENSE_ENTRY = "assets/grounds/legal/platform.txt"
    private const val PLATFORM_LICENSE_SOURCE = "platform-license"
    private const val CONTENT_LICENSE_SOURCE = "content-license"

    private val PACK_ICON_ARTWORK =
        ArtworkExpectation(
            32_575,
            "319a3bacb127ab5047b0373f86dff54934140b97192fb9d5552209be3fea210a",
        )

    private val TAB_ARTWORK =
        linkedMapOf(
            "tab/logo.png" to
                ArtworkExpectation(
                    35_236,
                    "6bbed2d5cceb0c34392d5fca7dbdc7257f989c0fd451e8ef240217de8189a7ae",
                ),
            "tab/badge_left.png" to
                ArtworkExpectation(
                    85,
                    "62d9bea27a1ce0452d1308f26ebdb259f8ebdec036a87db4476fe92c72fcce0d",
                ),
            "tab/badge_middle.png" to
                ArtworkExpectation(
                    81,
                    "7a2c1ae0457ac9ddfd76a4b9b9ae985c1772ffd1afa5f1ba6ed25428c299e6e8",
                ),
            "tab/badge_right.png" to
                ArtworkExpectation(
                    90,
                    "5ce1ffa4f916075b2038951635e8fb700b92df1d0cd4d28abb8123c772754c24",
                ),
        )

    private val TAB_PACK_PATHS =
        linkedMapOf(
            "tab/logo.png" to "assets/grounds/textures/font/tab_logo.png",
            "tab/badge_left.png" to "assets/grounds/textures/font/tab_badge_left.png",
            "tab/badge_middle.png" to "assets/grounds/textures/font/tab_badge_middle.png",
            "tab/badge_right.png" to "assets/grounds/textures/font/tab_badge_right.png",
        )

    private val CHAT_ARTWORK =
        linkedMapOf(
            CHAT_LOGO_SOURCE to
                ArtworkExpectation(
                    32_575,
                    "319a3bacb127ab5047b0373f86dff54934140b97192fb9d5552209be3fea210a",
                )
        )

    /**
     * Dialog-body menu sprites; `menu/x.png` ships as `assets/grounds/textures/font/menu/x.png`.
     */
    private val MENU_ARTWORK =
        linkedMapOf(
            "menu/panel.png" to
                ArtworkExpectation(
                    407,
                    "0cd2c7f7a70a53701b6ce5e75ec6efe8fed7209701a5ffecd0a4363ba9f545db",
                ),
            "menu/row.png" to
                ArtworkExpectation(
                    147,
                    "e25804d9002924bd2bd1cdfdbb09993c098cfb769540af4bce5aab1effdabdd9",
                ),
            "menu/toggle_on.png" to
                ArtworkExpectation(
                    129,
                    "00874ff90dd9484221fdf5fc739d50d76a6b7cb8eb075637d3ae644cdea0006c",
                ),
            "menu/toggle_off.png" to
                ArtworkExpectation(
                    126,
                    "ede564a42b07d0670bdc3a29086cce9a436098cb0df39729c4c1163e292f5fab",
                ),
            "menu/arrow_left.png" to
                ArtworkExpectation(
                    128,
                    "197849612738a0ce2f9e96f665ac22c0a757b22678f359f948d7f9e53c068a97",
                ),
            "menu/arrow_right.png" to
                ArtworkExpectation(
                    131,
                    "e89e9396dbd5d40192dd9e385f3d30f7357cb9932d829ec53ae84ae332389415",
                ),
            "menu/settings_panel.png" to
                ArtworkExpectation(
                    562,
                    "98e0568c2203b21a9e86cda8085b2c3be6767378a369d35b8a60aee1898ba983",
                ),
            "menu/divider.png" to
                ArtworkExpectation(
                    94,
                    "45cb340e1b031ede8d295e4f2ce4015ef7094ac28b7ef694d36d03c244cf8876",
                ),
            "menu/hit_1.png" to
                ArtworkExpectation(
                    73,
                    "d5be3a3ebb8695ea5be637929f38f875fd07926d5b79006fa6bd9884e60c8e56",
                ),
            "menu/hit_128.png" to
                ArtworkExpectation(
                    106,
                    "1f6753c42d4032a5ddc4cbd00eabad4227eba82f25515505877dab0658591030",
                ),
            "menu/hit_16.png" to
                ArtworkExpectation(
                    82,
                    "88f145d2abf6b9397999173941f154e6b8e07df121ff0d27ef227c181a2153e9",
                ),
            "menu/hit_2.png" to
                ArtworkExpectation(
                    75,
                    "469f4de97eb8a644185fa5798cfb77fd67810f5d387c30d62abce9595f6a8333",
                ),
            "menu/hit_256.png" to
                ArtworkExpectation(
                    112,
                    "7b7e1383c5df8190b6207869dea266983ba783681ec98c82ae9263e990acb45c",
                ),
            "menu/hit_32.png" to
                ArtworkExpectation(
                    87,
                    "1955ee969239908c59c5a2d94bb882b67d31fae7f19fd1620ae3e804cbb49e62",
                ),
            "menu/hit_4.png" to
                ArtworkExpectation(
                    75,
                    "4c46856d212d19c456a007df3c5a7ff8d96c7de720ed924f33cec805db6c35bf",
                ),
            "menu/hit_64.png" to
                ArtworkExpectation(
                    100,
                    "1d68d2246495c5d023d77627960f2bf1778bc6365c089aff93cf38b8526d7b5d",
                ),
            "menu/hit_8.png" to
                ArtworkExpectation(
                    79,
                    "99d24af41f65a9b552ec106494c39d8252442eb556c7f5bbf538f7c57db9378e",
                ),
            "menu/settings_panel_2.png" to
                ArtworkExpectation(
                    640,
                    "a626782a8f6e625d965224505dbe893016609261cab62d7f84c776c381efc126",
                ),
            "menu/nav_panel_3.png" to
                ArtworkExpectation(
                    514,
                    "4981898f5704406e607dc6a0571a282bd9b4db7e17aecd127df94994519bd212",
                ),
            "menu/tabs_content_3_l.png" to
                ArtworkExpectation(
                    622,
                    "b2c7c94cca510d184ef7488b1411a2e1bff1935e2134c17bfea04ec36ea42f7c",
                ),
            "menu/tabs_content_3_r.png" to
                ArtworkExpectation(
                    624,
                    "9c07bf8537e3a4db84a765aaaee231c9554e1a8218b198e713f6d9611496c897",
                ),
            "menu/map_sea3_l.png" to
                ArtworkExpectation(
                    2281,
                    "fe233ff66f5f3b0bf42c9019fbbf2a99b909d1d4eee4d347fed082ab79efdb50",
                ),
            "menu/map_sea3_r.png" to
                ArtworkExpectation(
                    2860,
                    "499a29a22e97059e84f6e0489fabc779f9071e35788b6628e4f5aaebfc2a7845",
                ),
            "menu/map3_na.png" to
                ArtworkExpectation(
                    1220,
                    "b017c1918000e3e92e4de34959634e69f99bc2ff3efdcafddd75e4cd49f31e01",
                ),
            "menu/map3_sa.png" to
                ArtworkExpectation(
                    437,
                    "7ea0125a44adea449f3ef461ff63a815598cde0522452cf88f58cb27e17fdb5a",
                ),
            "menu/map3_eu.png" to
                ArtworkExpectation(
                    651,
                    "84e76f79a43b052e571fc7bb73d481516ab1bc91ccba807483fe1609e97d47c8",
                ),
            "menu/map3_af.png" to
                ArtworkExpectation(
                    520,
                    "13f87ebdc4add333df7cbeab960a2626d09494ffb6274a1ab936637aaf00cbcf",
                ),
            "menu/map3_as.png" to
                ArtworkExpectation(
                    1343,
                    "29f0e45d7e2d8a985fddf04fe599adb14c7a634c8ec48bd6c387e0ff6e6b80f1",
                ),
            "menu/map3_oc.png" to
                ArtworkExpectation(
                    429,
                    "630c54660975e1c1c71e9e4974a7017cc5a288eae046fd2921cd44eb64943717",
                ),
            "menu/map3_na_picked.png" to
                ArtworkExpectation(
                    1627,
                    "95a276de1f225a15bfb976b417a8a5f59a190d8bfa3f6d0c894232d1eebfc155",
                ),
            "menu/map3_eu_picked.png" to
                ArtworkExpectation(
                    846,
                    "2f87894daf284022d26505a0da7827d1270bc0e870c1cb2b5fbe36cf4a719763",
                ),
            "menu/map3_as_picked.png" to
                ArtworkExpectation(
                    1820,
                    "f927c2e44a1f9276626cbf8a5ce988527956881033a6da0498ad918a9aeb82aa",
                ),
            "menu/map3_oc_picked.png" to
                ArtworkExpectation(
                    593,
                    "69862c37ce001e16be508105844eb5d320e051047962d7c313501e7f8b1ae1ac",
                ),
            "menu/map3_sa_picked.png" to
                ArtworkExpectation(
                    563,
                    "56da1577570d293392f7fa6f46816f0aa77988af436812d61e37f9db3e871115",
                ),
            "menu/map3_af_picked.png" to
                ArtworkExpectation(
                    685,
                    "3b3a423862206cd080de51d7d16c774530ddcff84ef980e4ef947fd8de316303",
                ),
            "menu/row_tall_l.png" to
                ArtworkExpectation(
                    162,
                    "7d779a3459663c93cf93f2af83fcec6eb094dc3e31d10b261e18fbcbcc924bc4",
                ),
            "menu/row_tall_r.png" to
                ArtworkExpectation(
                    165,
                    "8d359d9787a583b1e732a6e5296c264d439eec38f74ad9d037135eec1f102a70",
                ),
            "menu/icon_globe.png" to
                ArtworkExpectation(
                    205,
                    "b7cd1a03f046ffba54f00909c71534c533516aa9fb465babba69dc6aa89fbabd",
                ),
            "menu/icon_signal.png" to
                ArtworkExpectation(
                    157,
                    "1eb466558c2cd2b0ee51929abd6160f0ca63f64c6b52ed633af23f0abbcef0db",
                ),
            "menu/icon_hand.png" to
                ArtworkExpectation(
                    111,
                    "6c62fd61bffd86905c3495904c37241b98bcc0ee36dd77c92ff7627b1e3f39ad",
                ),
            "menu/pill_l.png" to
                ArtworkExpectation(
                    83,
                    "b225fc1a1c44bf1d2687c7a954fad81a9dd9107aff3c5a6e0851adcc970a1593",
                ),
            "menu/pill_r.png" to
                ArtworkExpectation(
                    83,
                    "2c73d3a21794734158c4ff5bf7d53cf7230102e6de115f044fea3b53295e08d6",
                ),
            "menu/pill_1.png" to
                ArtworkExpectation(
                    78,
                    "9e7cf515077d8847d485236cdd76d988625e74da8ddcbba550ecee5fefc56385",
                ),
            "menu/pill_2.png" to
                ArtworkExpectation(
                    82,
                    "769d4f55ecd3d727caa57581648aaba0f388ece3eba7ee80a818e06dda4a8d07",
                ),
            "menu/pill_4.png" to
                ArtworkExpectation(
                    83,
                    "775b2ddb642312a3900cd9fa58a8a80c639006f4b5297e2cc5f141158d0e03a8",
                ),
            "menu/pill_8.png" to
                ArtworkExpectation(
                    87,
                    "1f318131d3147c4b72e32dad477582561a3927c129d8b86d056a60ea79271c97",
                ),
            "menu/pill_16.png" to
                ArtworkExpectation(
                    90,
                    "51c1a79c13d1ce9c1282bf206515042bb3c471b195d8c10889430bca093ce164",
                ),
            "menu/pill_32.png" to
                ArtworkExpectation(
                    94,
                    "711db4d2b1803ecfc0d7898bf4afdbffb761f6d9fa3cc58d6617ab427862a765",
                ),
            "menu/pill_64.png" to
                ArtworkExpectation(
                    113,
                    "2f28036b823590780689ee8e49562ab01f4d2d33691c3d2cc3b8c3bf8a4b494b",
                ),
            "menu/pill_128.png" to
                ArtworkExpectation(
                    122,
                    "b666e26fa1b77e86debda3a01c7c260def8a0e96b883aea64b60e96fa638912c",
                ),
            "menu/card_na.png" to
                ArtworkExpectation(
                    302,
                    "5dd184f865e9c70d6bd0ea62219c40a29e1420408e6d8e989f7bdaed79dcd458",
                ),
            "menu/card_sa.png" to
                ArtworkExpectation(
                    213,
                    "bc793640443b0ec55f4ab1f765e832c44119cd6035204e4c0998d0c9a4e37446",
                ),
            "menu/card_eu.png" to
                ArtworkExpectation(
                    357,
                    "ec8e5842ca124705b37fd3058554b7a6a36d4c94247df988e8734b36c80016b2",
                ),
            "menu/card_af.png" to
                ArtworkExpectation(
                    243,
                    "7ca0969dfe73de4fe80aa7f3d59bd86566ec0f330eb51568adc2d2df0ccf831f",
                ),
            "menu/card_as.png" to
                ArtworkExpectation(
                    337,
                    "0d52a979191e3224955cafb33cc26122922d239b0fd0f2ccca14ddfa030241b9",
                ),
            "menu/card_oc.png" to
                ArtworkExpectation(
                    298,
                    "850978003e42e0566872dbf6ef2f7c0999b9950c18c15a23f40eb03f3f2b6b54",
                ),
            "menu/nav_panel_2.png" to
                ArtworkExpectation(
                    502,
                    "eb6c40c763b40c424fb382a13b88b97d630d77d2188de271f4cae36a212872ab",
                ),
            "menu/row_wide_l.png" to
                ArtworkExpectation(
                    138,
                    "bb98a7bbd49a9fbbfba0cef18e13fff08fa61799eb500e460d6f8a8e72ef7c5a",
                ),
            "menu/row_wide_r.png" to
                ArtworkExpectation(
                    141,
                    "78dd9707f4a9a3bcfb7815bdff4bd7484f12dee1ba63e2bd65c6eb6269db123f",
                ),
            "menu/region_panel_l.png" to
                ArtworkExpectation(
                    606,
                    "f5dc182d9d049992c5a87401dac73a4078f8dd3d9f09d6ec4f7349bb24052121",
                ),
            "menu/region_panel_r.png" to
                ArtworkExpectation(
                    608,
                    "2b71987127d825fc8a0fec62ff993a057c2b6ee429a3a9bd9c8fb2fa506b8673",
                ),
            "menu/map_sea_l.png" to
                ArtworkExpectation(
                    438,
                    "fcb9ffb65e4332d9b688f72389424738f5a95bb398445b7dc9e7fd639d0b6a53",
                ),
            "menu/map_sea_r.png" to
                ArtworkExpectation(
                    441,
                    "f9d3ad43c342e60c0b7496bfbb8ae757a41983fbcaeab1444fb38f8a4393576d",
                ),
            "menu/map2_na.png" to
                ArtworkExpectation(
                    1120,
                    "b197322ee1463745b5b5e55c36c68e35d4e3a0b0ec827da4dec2211d804d91e7",
                ),
            "menu/map2_sa.png" to
                ArtworkExpectation(
                    399,
                    "fa11f6c37e22857e78e93773b9b349b73ea7425b569c47300381a7525b6737e2",
                ),
            "menu/map2_eu.png" to
                ArtworkExpectation(
                    599,
                    "eceef338db9228c97f82dc0a676038915e5105deae94b8f10df09199ee778d3e",
                ),
            "menu/map2_af.png" to
                ArtworkExpectation(
                    468,
                    "09ae3b8c03084041f8943c41ccd173e466bde56121291ac521e0c530344da3ef",
                ),
            "menu/map2_as.png" to
                ArtworkExpectation(
                    1244,
                    "d48aff6683609f93b2145bde2cb2e46773d318575bb0844a8073bcf4917e4376",
                ),
            "menu/map2_oc.png" to
                ArtworkExpectation(
                    395,
                    "2b706d442983b972d6a9d333611edcd16f8c3574235ffc58236a15530f544d3c",
                ),
            "menu/map2_na_picked.png" to
                ArtworkExpectation(
                    1120,
                    "3eff6b9f109ee4897c238450349af2b11bc05949db4ddbf7df5ab5839be5b183",
                ),
            "menu/map2_eu_picked.png" to
                ArtworkExpectation(
                    599,
                    "208c9b87d27a4626d1fa8c5e5d202fb53a1b99feba278a062f9adf5dcb361fc5",
                ),
            "menu/map2_as_picked.png" to
                ArtworkExpectation(
                    1244,
                    "2115b457c9e8e3f41dff55c00b4239a0bc9fd9cb48977940ed2ddfbc9131f5e9",
                ),
            "menu/map2_oc_picked.png" to
                ArtworkExpectation(
                    395,
                    "60e4d1cc774e656c3b9e40aae357f20215d4f66a424ba1682ac1195ab411bc67",
                ),
            "menu/region_panel.png" to
                ArtworkExpectation(
                    588,
                    "8e5825f5b397b92f7eaa1099104158d4b15bef043c32420a550b8a4728011675",
                ),
            "menu/map_sea.png" to
                ArtworkExpectation(
                    348,
                    "38fb275453a1724d315966f2963bb6beefa6e425fd5ab2d44e1d4ea268de0cd5",
                ),
            "menu/map_na.png" to
                ArtworkExpectation(
                    670,
                    "bf03a0f2418b5b966d262f6897a3f792eef2f6b4d9ff6f42bd5e35ae764baf6b",
                ),
            "menu/map_sa.png" to
                ArtworkExpectation(
                    266,
                    "4f3b95637701e453eb2e05b334a00bfd2a1f17a131bdbeb4f5ef7f100307d8c5",
                ),
            "menu/map_eu.png" to
                ArtworkExpectation(
                    365,
                    "b156b091199e938a8232364163c3c6c36fcffbbd488c84096c7e4e86ce55a74d",
                ),
            "menu/map_af.png" to
                ArtworkExpectation(
                    321,
                    "d301eeb129615f9fb6ef3bd1be936cfa5f01a66d539a1cdc5498d41f379cd29a",
                ),
            "menu/map_as.png" to
                ArtworkExpectation(
                    827,
                    "52b8935b10cab896175dc4e9b534410be4e1e593b37db9e100224e7fc79ac18b",
                ),
            "menu/map_oc.png" to
                ArtworkExpectation(
                    262,
                    "69f44ab1be75c115573a93cc26d44ae649f2d139559e457c183bb6c75030ddeb",
                ),
            "menu/map_na_picked.png" to
                ArtworkExpectation(
                    670,
                    "2f81d49dbd2e3dfddac83f618f68feed8860c2fcbfba6d43b5a06936923cf55b",
                ),
            "menu/map_eu_picked.png" to
                ArtworkExpectation(
                    365,
                    "1568a685e3b4a02b628ce36e97bf8dc0cb0f8622b0a35bd92085dad8cb385ccd",
                ),
            "menu/map_as_picked.png" to
                ArtworkExpectation(
                    827,
                    "00ef35622d34c5c8e1c4a283bf9b08466442d1c2ff5cb84a29cc815cc101b650",
                ),
            "menu/map_oc_picked.png" to
                ArtworkExpectation(
                    262,
                    "05cc8de6a4392d796e58d9f9cf46f740ab521a7dc5b5c6851aba1ba55119faa2",
                ),
            "menu/map_zone.png" to
                ArtworkExpectation(
                    81,
                    "1d2da3bbcfc5868db44a3b8016075fba0158c0b2fc1eb945a27bd3211171ac12",
                ),
            "menu/nav_panel.png" to
                ArtworkExpectation(
                    430,
                    "1a233e1ec812f194a0bbe3d951697a394557d7146d81e75c3cfa60985686ca47",
                ),
            "menu/tabs_content_panel.png" to
                ArtworkExpectation(
                    562,
                    "98e0568c2203b21a9e86cda8085b2c3be6767378a369d35b8a60aee1898ba983",
                ),
            "menu/nav_button.png" to
                ArtworkExpectation(
                    133,
                    "5bac550c855ad2677ae9bac6f24926fa0305ec6170cbfe0852ec43918f777f42",
                ),
            "menu/nav_selected.png" to
                ArtworkExpectation(
                    139,
                    "caa178905b842d568fc0a9b3d38918b3fe7a0ff40d28f9c637b845cc7c002b7d",
                ),
        )

    private val PLATFORM_ARTWORK =
        linkedMapOf(
            "frames/hover.png" to
                ArtworkExpectation(
                    151,
                    "73460b5b00c8d5f984dd274c8852c44dd05f8819679691f92e624f8ada3e9a31",
                ),
            "icons/back.png" to
                ArtworkExpectation(
                    111,
                    "651f99c806c5d57f204d4505a9452d0a9e51f1e63ea85bc8fb22be6ea6805441",
                ),
            "icons/blank.png" to
                ArtworkExpectation(
                    75,
                    "a901afae7bdb66678f08a39b32f8a46da9864c8a64fabc0e77a7f12b93df12ba",
                ),
            "icons/close.png" to
                ArtworkExpectation(
                    139,
                    "7765185bdc454529602d98c0e9551c97063e6ee09ed4537fa4845103d2015313",
                ),
            "icons/next.png" to
                ArtworkExpectation(
                    118,
                    "dfa66166eefc04906f4d2d07d93a9b18249143def5d0b156c1343d1e5b151738",
                ),
            "panels/menu.png" to
                ArtworkExpectation(
                    681,
                    "9e9e6760d0ce3562000b11c6a6e9eb84d20bdd59f9d7dc9c5a72dc51c856fd64",
                ),
            "tooltips/default_bg.png" to
                ArtworkExpectation(
                    94,
                    "364059a08c651259e009cdd0aeb98f36379f6db113917a23674469ff27de3f48",
                ),
            "tooltips/default_frame.png" to
                ArtworkExpectation(
                    112,
                    "a1c5355ab5945428d821a62a0eab7948f04443c7622e1fbd96ea67776e6da966",
                ),
        )
}
