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
    fun `content art directory ships no image or archive files`() {
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
                "art/platform/panels/menu.png",
                "art/platform/tab/badge_left.png",
                "art/platform/tab/badge_middle.png",
                "art/platform/tab/badge_right.png",
                "art/platform/tab/logo.png",
                "art/platform/tooltips/default_bg.png",
                "art/platform/tooltips/default_frame.png",
            )
        val ALLOWED_CONTENT_ART_FILES = setOf(".gitkeep", "LICENSE")
    }
}
