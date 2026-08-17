package gg.grounds.resourcepacks.client

import java.lang.classfile.ClassFile
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientJarBoundaryTest {
    @Test
    fun `final client JAR contains only client classes and Kotlin metadata`() {
        val entries =
            JarFile(clientJar().toFile()).use { jar ->
                jar.entries().asSequence().map { it.name }.filterNot { it.endsWith('/') }.toSet()
            }

        assertTrue(entries.all(::allowedEntry), entries.filterNot(::allowedEntry).joinToString())
        assertEquals(expectedOwners(), publicOwners(clientJar()))
    }

    @Test
    fun `final public signatures expose only JDK contract and client types`() {
        val descriptors = publicDescriptors(clientJar())

        assertTrue(descriptors.isNotEmpty())
        assertTrue(
            descriptors.all(::hasOnlyApprovedDescriptorTypes),
            descriptors.filterNot(::hasOnlyApprovedDescriptorTypes).joinToString(),
        )
        listOf("jackson", "velocity", "minestom", "nats", "plugin/config").forEach { forbidden ->
            assertFalse(descriptors.any { it.contains(forbidden, ignoreCase = true) })
        }
    }

    private fun clientJar() = Path.of(System.getProperty("client.jar"))

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
                                (it.flags().has(AccessFlag.PUBLIC) ||
                                    it.flags().has(AccessFlag.PROTECTED)) &&
                                    !it.flags().has(AccessFlag.SYNTHETIC)
                            }
                            .map { it.methodType().stringValue() }
                }
                .toList()
        }

    private fun hasOnlyApprovedDescriptorTypes(descriptor: String): Boolean =
        Regex("L([^;]+);")
            .findAll(descriptor)
            .map { it.groupValues[1] }
            .all {
                it.startsWith("java/") ||
                    it.startsWith("gg/grounds/resourcepacks/contract/") ||
                    it.startsWith("gg/grounds/resourcepacks/client/") ||
                    it == "kotlin/enums/EnumEntries"
            }

    private fun expectedOwners() =
        setOf(
            "gg.grounds.resourcepacks.client.BoundedResponseReader",
            "gg.grounds.resourcepacks.client.JdkPackSetHttpTransport",
            "gg.grounds.resourcepacks.client.PackSetClient",
            "gg.grounds.resourcepacks.client.PackSetClientConfig",
            "gg.grounds.resourcepacks.client.PackSetClientState",
            "gg.grounds.resourcepacks.client.PackSetClientStatus",
            "gg.grounds.resourcepacks.client.PackSetDiskCache",
            "gg.grounds.resourcepacks.client.PackSetHttpResponse",
            "gg.grounds.resourcepacks.client.PackSetHttpTransport",
            "gg.grounds.resourcepacks.client.PackSetResolver",
            "gg.grounds.resourcepacks.client.PackSetSnapshot",
            "gg.grounds.resourcepacks.client.PackSetSource",
            "gg.grounds.resourcepacks.client.PackSetStateListener",
            "gg.grounds.resourcepacks.client.RefreshResult",
            "gg.grounds.resourcepacks.client.ResolvedPack",
            "gg.grounds.resourcepacks.client.ResolverCache",
            "gg.grounds.resourcepacks.client.RetryPolicy",
        )

    private fun allowedEntry(entry: String): Boolean =
        entry == "META-INF/MANIFEST.MF" ||
            entry == "META-INF/resourcepacks-client.kotlin_module" ||
            entry.startsWith("gg/grounds/resourcepacks/client/")
}
