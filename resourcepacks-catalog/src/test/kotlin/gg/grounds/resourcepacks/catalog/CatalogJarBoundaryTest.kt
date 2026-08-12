package gg.grounds.resourcepacks.catalog

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogJarBoundaryTest {
    @Test
    fun `catalog JAR contains only catalog declarations and Kotlin metadata`() {
        val entries = JarFile(catalogJar().toFile()).use { jar ->
            jar.entries().asSequence().map { it.name }.filterNot { it.endsWith('/') }.toSet()
        }

        assertTrue(entries.all { it == "META-INF/MANIFEST.MF" || it == "META-INF/resourcepacks-catalog.kotlin_module" || it.startsWith("gg/grounds/resourcepacks/catalog/") })
        assertFalse(entries.any { it.contains("jackson", ignoreCase = true) || it.contains("product", ignoreCase = true) })
    }

    private fun catalogJar(): Path {
        val jar =
            generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { it.resolve("settings.gradle.kts").exists() }
                .resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")
        check(jar.exists()) { "Expected catalog JAR at $jar" }
        return jar
    }
}
