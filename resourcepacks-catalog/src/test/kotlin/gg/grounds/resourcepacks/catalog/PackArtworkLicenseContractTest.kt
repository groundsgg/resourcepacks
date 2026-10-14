package gg.grounds.resourcepacks.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackArtworkLicenseContractTest {
    private val root: Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    @Test
    fun `platform license keeps first-party artwork proprietary`() {
        val license = Files.readString(root.resolve("art/platform/LICENSE"))
        assertTrue(license.contains("all rights reserved", ignoreCase = true))
        assertTrue(
            license.contains("not licensed under AGPL", ignoreCase = true) ||
                license.contains("not sublicensed", ignoreCase = true)
        )
        assertTrue(license.contains("first-party", ignoreCase = true))
        assertTrue(license.contains("commercial marketplace", ignoreCase = true))
        assertTrue(license.contains("MCModels"))
        assertTrue(license.contains("art/content"))
        assertFalse(license.contains("CC-BY-NC-SA"))
        assertFalse(license.contains("CC BY-NC-SA"))
    }

    @Test
    fun `readme licensing carves artwork out of agpl`() {
        val readme = Files.readString(root.resolve("README.md"))
        val licensing = readme.substringAfter("## Licensing", missingDelimiterValue = "")
        assertTrue(licensing.isNotBlank(), "README must have a Licensing section")
        assertTrue(licensing.contains("AGPL-3.0-only"))
        assertTrue(licensing.contains("art/platform"))
        assertTrue(licensing.contains("art/content"))
        assertTrue(
            licensing.contains("all rights reserved", ignoreCase = true) ||
                licensing.contains("proprietary", ignoreCase = true)
        )
        assertFalse(licensing.contains("CC-BY-NC-SA"))
    }

    @Test
    fun `asset origins name approved first-party art`() {
        val manifest = Files.readString(root.resolve("art/platform/ASSET_ORIGINS.json"))
        val copiesKey = manifest.indexOf("\"approvedExampleCopies\"")
        assertTrue(copiesKey >= 0, "ASSET_ORIGINS.json must include approvedExampleCopies")
        val originalsKey = manifest.indexOf("\"firstPartyOriginals\"")
        val approvedExampleCopies =
            if (originalsKey >= 0) manifest.substring(copiesKey, originalsKey)
            else manifest.substring(copiesKey)
        val sources =
            Regex(""""source"\s*:\s*"([^"]+)"""")
                .findAll(approvedExampleCopies)
                .map { it.groupValues[1] }
                .toList()
        assertTrue(sources.isNotEmpty(), "ASSET_ORIGINS.json must list first-party sources")
        sources.forEach { source ->
            assertTrue(
                source.startsWith(FIRST_PARTY_SOURCE_PREFIX),
                "Origin $source is not approved first-party art",
            )
            assertFalse(
                source.contains("mcmodels", ignoreCase = true),
                "Origin $source names a commercial marketplace",
            )
        }
    }

    @Test
    fun `repository image files are the approved first-party platform set`() {
        val actual = linkedSetOf<String>()
        Files.walk(root).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path) &&
                        IMAGE_EXTENSIONS.any { path.fileName.toString().endsWith(it) } &&
                        !isSkipped(root.relativize(path))
                }
                .forEach { path -> actual += root.relativize(path).joinToString("/") }
        }
        assertEquals(APPROVED_PLATFORM_IMAGES, actual)
    }

    @Test
    fun `content art directory ships only licensed authored scene bootstrap JSON`() {
        val contentArt = root.resolve("art/content")
        require(Files.isDirectory(contentArt)) { "Missing art/content directory" }
        val actual = linkedSetOf<String>()
        Files.walk(contentArt).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .forEach { path ->
                    actual += contentArt.relativize(path).joinToString("/") { it.toString() }
                }
        }
        assertEquals(ALLOWED_CONTENT_ART_FILES, actual)
        actual.forEach { name ->
            assertTrue(
                IMAGE_EXTENSIONS.none { name.endsWith(it) } && !name.endsWith(".zip"),
                "Content art must not ship $name",
            )
        }
    }

    @Test
    fun `content license keeps vendor files under the seller licence`() {
        val license = Files.readString(root.resolve("art/content/LICENSE"))
        assertTrue(license.contains("vendor", ignoreCase = true))
        assertTrue(license.contains("MCModels"))
        assertTrue(license.contains("Grounds-authored"))
        assertTrue(
            license.contains("not licensed under AGPL", ignoreCase = true) ||
                license.contains("not sublicensed", ignoreCase = true)
        )
    }

    @Test
    fun `product license carves artwork out of agpl`() {
        val license = Files.readString(root.resolve("resourcepacks-product/LICENSE"))
        assertTrue(license.contains("AGPL-3.0"))
        assertTrue(license.contains("art/platform"))
        assertTrue(license.contains("art/content"))
        assertTrue(
            license.contains("not sublicensed", ignoreCase = true) ||
                license.contains("all rights reserved", ignoreCase = true)
        )
    }

    private fun isSkipped(relative: Path): Boolean {
        val first = relative.firstOrNull()?.toString() ?: return false
        return first in SKIP_ROOTS || relative.any { it.toString() == "build" }
    }

    private companion object {
        const val FIRST_PARTY_SOURCE_PREFIX = "library-gui/examples/theme-demo/art/"
        val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".tga", ".bmp")
        val SKIP_ROOTS =
            setOf(
                ".git",
                ".gradle",
                ".idea",
                ".worktrees",
                ".superpowers",
                ".cursor",
                "node_modules",
            )
        val APPROVED_PLATFORM_IMAGES =
            setOf(
                "art/platform/pack.png",
                "art/platform/frames/hover.png",
                "art/platform/icons/back.png",
                "art/platform/icons/blank.png",
                "art/platform/icons/close.png",
                "art/platform/icons/next.png",
                "art/platform/menu/arrow_left.png",
                "art/platform/menu/arrow_right.png",
                "art/platform/menu/divider.png",
                "art/platform/menu/hit_1.png",
                "art/platform/menu/hit_128.png",
                "art/platform/menu/hit_16.png",
                "art/platform/menu/hit_2.png",
                "art/platform/menu/hit_256.png",
                "art/platform/menu/hit_32.png",
                "art/platform/menu/hit_4.png",
                "art/platform/menu/hit_64.png",
                "art/platform/menu/hit_8.png",
                "art/platform/menu/map2_af.png",
                "art/platform/menu/map2_as.png",
                "art/platform/menu/map2_as_picked.png",
                "art/platform/menu/map2_eu.png",
                "art/platform/menu/map2_eu_picked.png",
                "art/platform/menu/map2_na.png",
                "art/platform/menu/map2_na_picked.png",
                "art/platform/menu/map2_oc.png",
                "art/platform/menu/map2_oc_picked.png",
                "art/platform/menu/map2_sa.png",
                "art/platform/menu/map_af.png",
                "art/platform/menu/map_as.png",
                "art/platform/menu/map_as_picked.png",
                "art/platform/menu/map_eu.png",
                "art/platform/menu/map_eu_picked.png",
                "art/platform/menu/map_na.png",
                "art/platform/menu/map_na_picked.png",
                "art/platform/menu/map_oc.png",
                "art/platform/menu/map_oc_picked.png",
                "art/platform/menu/map_sa.png",
                "art/platform/menu/map_sea.png",
                "art/platform/menu/map_sea_l.png",
                "art/platform/menu/map_sea_r.png",
                "art/platform/menu/map_zone.png",
                "art/platform/menu/nav_button.png",
                "art/platform/menu/nav_panel.png",
                "art/platform/menu/nav_panel_2.png",
                "art/platform/menu/nav_selected.png",
                "art/platform/menu/panel.png",
                "art/platform/menu/region_panel.png",
                "art/platform/menu/region_panel_l.png",
                "art/platform/menu/region_panel_r.png",
                "art/platform/menu/row.png",
                "art/platform/menu/row_wide_l.png",
                "art/platform/menu/row_wide_r.png",
                "art/platform/menu/settings_panel.png",
                "art/platform/menu/settings_panel_2.png",
                "art/platform/menu/tabs_content_panel.png",
                "art/platform/menu/toggle_off.png",
                "art/platform/menu/toggle_on.png",
                "art/platform/panels/menu.png",
                "art/platform/tab/badge_left.png",
                "art/platform/tab/badge_middle.png",
                "art/platform/tab/badge_right.png",
                "art/platform/tab/logo.png",
                "art/platform/chat/logo.png",
                "art/platform/tooltips/default_bg.png",
                "art/platform/tooltips/default_frame.png",
            )
        val ALLOWED_CONTENT_ART_FILES =
            setOf(
                ".gitkeep",
                "LICENSE",
                "models/editor/marker.json",
                "models/npc_bodies/editor/guide.json",
            )
    }
}
