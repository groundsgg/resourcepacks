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
            "menu/nav_button.png" to
                ArtworkExpectation(
                    133,
                    "5bac550c855ad2677ae9bac6f24926fa0305ec6170cbfe0852ec43918f777f42",
                ),
            "menu/nav_panel.png" to
                ArtworkExpectation(
                    430,
                    "1a233e1ec812f194a0bbe3d951697a394557d7146d81e75c3cfa60985686ca47",
                ),
            "menu/nav_selected.png" to
                ArtworkExpectation(
                    139,
                    "caa178905b842d568fc0a9b3d38918b3fe7a0ff40d28f9c637b845cc7c002b7d",
                ),
            "menu/tabs_content_panel.png" to
                ArtworkExpectation(
                    562,
                    "98e0568c2203b21a9e86cda8085b2c3be6767378a369d35b8a60aee1898ba983",
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
