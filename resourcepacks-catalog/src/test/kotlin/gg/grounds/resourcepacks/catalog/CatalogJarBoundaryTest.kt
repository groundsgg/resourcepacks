package gg.grounds.resourcepacks.catalog

import java.util.jar.JarFile
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

    private fun catalogJar() = java.nio.file.Path.of(System.getProperty("catalog.jar"))
}
