package gg.grounds.resourcepacks.catalog

import java.lang.classfile.ClassFile
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogAbiManifestTest {
    @Test
    fun `final catalog JAR public ABI exactly matches manifest`() =
        assertEquals(expectedManifest(), effectivePublicAbi(catalogJar()))

    @Test
    fun `ABI gate rejects a real JAR mutation containing a zero member public owner`() {
        val mutated = copyWithExtraClass("injected.EmptyOwner", "public final class EmptyOwner {}")
        assertFailsWith<AssertionError> {
            assertEquals(expectedManifest(), effectivePublicAbi(mutated))
        }
    }

    @Test
    fun `ABI gate rejects a real JAR mutation adding a public member to an approved owner`() {
        val mutated =
            copyWithExtraClass(
                "gg.grounds.resourcepacks.catalog.GroundsAssets",
                "public final class GroundsAssets { public static final String LEAK = \"x\"; }",
            )
        assertFailsWith<AssertionError> {
            assertEquals(expectedManifest(), effectivePublicAbi(mutated))
        }
    }

    private fun expectedManifest() =
        requireNotNull(javaClass.classLoader.getResource("catalog-public-api.txt"))
            .readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .toSet()

    private fun catalogJar() = Path.of(System.getProperty("catalog.jar"))

    private fun effectivePublicAbi(jar: Path): Set<String> =
        JarFile(jar.toFile()).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".class") }
                .flatMap { entry ->
                    val model = ClassFile.of().parse(archive.getInputStream(entry).readBytes())
                    val owner = model.thisClass().asInternalName().replace('/', '.')
                    if (
                        !model.flags().has(AccessFlag.PUBLIC) &&
                            !model.flags().has(AccessFlag.PROTECTED)
                    )
                        emptySequence()
                    else
                        sequence {
                            yield("OWNER|$owner")
                            model
                                .fields()
                                .filter {
                                    it.flags().has(AccessFlag.PUBLIC) ||
                                        it.flags().has(AccessFlag.PROTECTED)
                                }
                                .forEach { field ->
                                    yield(
                                        "$owner|FIELD|${field.fieldName().stringValue()}|${descriptorType(field.fieldType().stringValue())}"
                                    )
                                }
                            model
                                .methods()
                                .filter {
                                    it.flags().has(AccessFlag.PUBLIC) ||
                                        it.flags().has(AccessFlag.PROTECTED)
                                }
                                .forEach { method ->
                                    val methodType =
                                        MethodTypeDesc.ofDescriptor(
                                            method.methodType().stringValue()
                                        )
                                    val parameters =
                                        methodType.parameterList().joinToString(",") {
                                            descriptorType(it.descriptorString())
                                        }
                                    val kind =
                                        if (method.methodName().stringValue() == "<init>")
                                            "CONSTRUCTOR"
                                        else "METHOD"
                                    val name =
                                        if (kind == "CONSTRUCTOR") "<init>"
                                        else method.methodName().stringValue()
                                    yield(
                                        "$owner|$kind|$name($parameters)|${if (kind == "CONSTRUCTOR") "void" else descriptorType(methodType.returnType().descriptorString())}"
                                    )
                                }
                        }
                }
                .toSet()
        }

    private fun descriptorType(descriptor: String): String =
        if (descriptor.startsWith('L'))
            descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
        else if (descriptor.startsWith('[')) descriptorType(descriptor.drop(1)) + "[]"
        else
            mapOf(
                    "V" to "void",
                    "Z" to "boolean",
                    "B" to "byte",
                    "C" to "char",
                    "S" to "short",
                    "I" to "int",
                    "J" to "long",
                    "F" to "float",
                    "D" to "double",
                )
                .getValue(descriptor)

    private fun copyWithExtraClass(name: String, source: String): Path {
        val root = kotlin.io.path.createTempDirectory("catalog-abi-mutation")
        val sourceFile = root.resolve(name.replace('.', '/') + ".java")
        java.nio.file.Files.createDirectories(sourceFile.parent)
        java.nio.file.Files.writeString(
            sourceFile,
            "package ${name.substringBeforeLast('.')}; $source",
        )
        check(
            javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", root.toString(), sourceFile.toString()) == 0
        )
        val target = root.resolve("mutated.jar")
        java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(target)).use { output ->
            val replacement = name.replace('.', '/') + ".class"
            JarFile(catalogJar().toFile()).use { original ->
                original
                    .entries()
                    .asSequence()
                    .filterNot { it.name == replacement }
                    .forEach { entry ->
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        original.getInputStream(entry).copyTo(output)
                        output.closeEntry()
                    }
            }
            val classFile = root.resolve(name.replace('.', '/') + ".class")
            output.putNextEntry(java.util.zip.ZipEntry(replacement))
            java.nio.file.Files.newInputStream(classFile).copyTo(output)
            output.closeEntry()
        }
        return target
    }
}
