package gg.grounds.resourcepacks.contract

import java.lang.classfile.ClassFile
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Final-artifact gate: public contract classes are the only API and no implementation is shaded.
 */
class ContractJarBoundaryTest {
    @Test
    fun `final contract JAR has the exact public owner set and no implementation payload`() {
        val entries =
            JarFile(contractJar().toFile()).use { jar ->
                jar.entries().asSequence().map { it.name }.filterNot { it.endsWith('/') }.toSet()
            }
        assertTrue(entries.all(::allowedEntry))
        assertFalse(
            entries.any { it.contains("jackson", ignoreCase = true) || it.contains("product/") }
        )

        assertEquals(expectedOwners(), publicOwners(contractJar()))
    }

    @Test
    fun `final public method signatures expose contract and JDK types only`() {
        val publicDescriptors = publicDescriptors(contractJar())
        assertTrue(publicDescriptors.isNotEmpty())
        assertFalse(publicDescriptors.any { it.contains("Ltools/jackson/") })
        assertFalse(publicDescriptors.any { it.contains("Lgg/grounds/resourcepacks/product/") })
    }

    @Test
    fun `JAR gate catches a real extra payload mutation`() {
        val directory = kotlin.io.path.createTempDirectory("contract-jar-mutation-")
        try {
            val mutated = directory.resolve("mutated.jar")
            JarFile(contractJar().toFile()).use { original ->
                java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(mutated)).use {
                    output ->
                    original.entries().asSequence().forEach { entry ->
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        original.getInputStream(entry).copyTo(output)
                        output.closeEntry()
                    }
                    output.putNextEntry(java.util.zip.ZipEntry("tools/jackson/Injected.class"))
                    output.write(byteArrayOf(0))
                    output.closeEntry()
                }
            }
            val entries =
                JarFile(mutated.toFile()).use {
                    it.entries().asSequence().map { entry -> entry.name }.toSet()
                }
            assertFalse(entries.all(::allowedEntry))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun contractJar() = Path.of(System.getProperty("contract.jar"))

    private fun publicOwners(jar: Path): Set<String> =
        JarFile(jar.toFile()).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".class") && '$' !in it.name }
                .map { ClassFile.of().parse(archive.getInputStream(it).readBytes()) }
                .filter { it.flags().has(AccessFlag.PUBLIC) }
                .map { it.thisClass().asInternalName().replace('/', '.') }
                .toSet()
        }

    private fun publicDescriptors(jar: Path): List<String> =
        JarFile(jar.toFile()).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".class") }
                .map { ClassFile.of().parse(archive.getInputStream(it).readBytes()) }
                .filter { it.flags().has(AccessFlag.PUBLIC) }
                .flatMap { model ->
                    model
                        .fields()
                        .asSequence()
                        .filter {
                            it.flags().has(AccessFlag.PUBLIC) ||
                                it.flags().has(AccessFlag.PROTECTED)
                        }
                        .map { it.fieldType().stringValue() } +
                        model
                            .methods()
                            .asSequence()
                            .filter {
                                it.flags().has(AccessFlag.PUBLIC) ||
                                    it.flags().has(AccessFlag.PROTECTED)
                            }
                            .map { it.methodType().stringValue() }
                }
                .toList()
        }

    private fun expectedOwners() =
        setOf(
            "gg.grounds.resourcepacks.contract.ManifestCatalog",
            "gg.grounds.resourcepacks.contract.ManifestDecodeResult",
            "gg.grounds.resourcepacks.contract.ManifestDiagnostic",
            "gg.grounds.resourcepacks.contract.ManifestDiagnosticCode",
            "gg.grounds.resourcepacks.contract.ManifestMinecraft",
            "gg.grounds.resourcepacks.contract.ManifestPack",
            "gg.grounds.resourcepacks.contract.ManifestParserLimits",
            "gg.grounds.resourcepacks.contract.ManifestProvenance",
            "gg.grounds.resourcepacks.contract.ManifestPublication",
            "gg.grounds.resourcepacks.contract.PackSetContractJson",
            "gg.grounds.resourcepacks.contract.PackSetManifest",
            "gg.grounds.resourcepacks.contract.PublicationType",
        )

    private fun allowedEntry(entry: String): Boolean =
        entry == "META-INF/MANIFEST.MF" ||
            entry == "META-INF/resourcepacks-contract.kotlin_module" ||
            entry.startsWith("gg/grounds/resourcepacks/contract/")
}
