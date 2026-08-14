package gg.grounds.resourcepacks.catalog

import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.ClassTransform
import java.lang.constant.ConstantDescs
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val baselineAbi = effectivePublicAbi(catalogJar())
        val mutated =
            copyWithAddedPublicStaticField("gg/grounds/resourcepacks/catalog/GroundsAssets.class")
        val mutatedAbi = effectivePublicAbi(mutated)
        assertTrue(mutatedAbi.containsAll(baselineAbi), "Mutation must preserve the baseline ABI")
        assertEquals(
            setOf("gg.grounds.resourcepacks.catalog.GroundsAssets|FIELD|LEAK|java.lang.String"),
            mutatedAbi - baselineAbi,
        )
        assertFailsWith<AssertionError> { assertEquals(expectedManifest(), mutatedAbi) }
    }

    @Test
    fun `top level public owner containing dollar is ABI without InnerClasses`() {
        val mutated =
            copyWithExtraClass("injected.Legal\$Owner", "public final class Legal\$Owner {}")
        val mutatedAbi = effectivePublicAbi(mutated)
        assertContains(mutatedAbi, "OWNER|injected.Legal\$Owner")
        assertFailsWith<AssertionError> { assertEquals(expectedManifest(), mutatedAbi) }
    }

    @Test
    fun `public inner under private outer is not ABI`() {
        val mutated =
            copyWithExtraClass(
                "injected.PublicRoot",
                "public class PublicRoot { private static class HiddenOuter { public static final class Inner {} } }",
            )
        val mutatedAbi = effectivePublicAbi(mutated)
        assertFalse("OWNER|injected.PublicRoot\$HiddenOuter\$Inner" in mutatedAbi)
    }

    @Test
    fun `public inner under public outer is ABI`() {
        val mutated =
            copyWithExtraClass(
                "injected.PublicOuter",
                "public class PublicOuter { public static final class Inner {} }",
            )
        val mutatedAbi = effectivePublicAbi(mutated)
        assertContains(mutatedAbi, "OWNER|injected.PublicOuter\$Inner")
        assertFailsWith<AssertionError> { assertEquals(expectedManifest(), mutatedAbi) }
    }

    @Test
    fun `duplicate class internal names fail closed with stable diagnostic`() {
        val duplicate =
            copyWithConflictingClassEntry(
                "gg/grounds/resourcepacks/catalog/GroundsAssets.class",
                "conflict/GroundsAssets.class",
            )
        val failure = assertFailsWith<IllegalStateException> { effectivePublicAbi(duplicate) }
        assertEquals(
            "Duplicate class internal name gg/grounds/resourcepacks/catalog/GroundsAssets in JAR entries: conflict/GroundsAssets.class, gg/grounds/resourcepacks/catalog/GroundsAssets.class",
            failure.message,
        )
    }

    private fun copyWithConflictingClassEntry(sourceEntry: String, duplicateEntry: String): Path {
        val root = kotlin.io.path.createTempDirectory("catalog-abi-duplicate")
        val target = root.resolve("duplicate.jar")
        JarFile(catalogJar().toFile()).use { original ->
            val duplicateBytes =
                addPublicStaticLeak(
                    original
                        .getInputStream(checkNotNull(original.getJarEntry(sourceEntry)))
                        .readBytes()
                )
            java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(target)).use { output
                ->
                original.entries().asSequence().forEach { entry ->
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    original.getInputStream(entry).copyTo(output)
                    output.closeEntry()
                }
                output.putNextEntry(java.util.zip.ZipEntry(duplicateEntry))
                output.write(duplicateBytes)
                output.closeEntry()
            }
        }
        return target
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
            val classes =
                archive
                    .entries()
                    .asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { entry ->
                        val model = ClassFile.of().parse(archive.getInputStream(entry).readBytes())
                        ParsedClass(entry.name, model)
                    }
                    .toList()
            val duplicate =
                classes
                    .groupBy { it.model.thisClass().asInternalName() }
                    .filterValues { it.size > 1 }
                    .toSortedMap()
                    .entries
                    .firstOrNull()
            check(duplicate == null) {
                "Duplicate class internal name ${duplicate!!.key} in JAR entries: ${duplicate.value.map(ParsedClass::entryName).sorted().joinToString()}"
            }
            val models = classes.associate { it.model.thisClass().asInternalName() to it.model }
            models.values
                .asSequence()
                .flatMap { model ->
                    val owner = model.thisClass().asInternalName().replace('/', '.')
                    if (
                        !model.flags().has(AccessFlag.PUBLIC) &&
                            !model.flags().has(AccessFlag.PROTECTED) ||
                            !isEffectivelyAccessible(model, models)
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

    private fun isEffectivelyAccessible(
        model: ClassModel,
        models: Map<String, ClassModel>,
    ): Boolean {
        if (!model.flags().has(AccessFlag.PUBLIC) && !model.flags().has(AccessFlag.PROTECTED))
            return false
        val name = model.thisClass().asInternalName()
        val relation =
            model.findAttribute(Attributes.innerClasses()).orElse(null)?.classes()?.firstOrNull {
                it.innerClass().asInternalName() == name
            }
        val enclosing = model.findAttribute(Attributes.enclosingMethod())
        if (relation == null && enclosing.isPresent) error("Malformed enclosing relation for $name")
        if (relation == null) return true
        if (!relation.has(AccessFlag.PUBLIC) && !relation.has(AccessFlag.PROTECTED)) return false
        val outer =
            relation
                .outerClass()
                .orElseThrow { error("Missing outer relation for $name") }
                .asInternalName()
        return isEffectivelyAccessible(models[outer] ?: error("Missing outer $outer"), models)
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
            java.nio.file.Files.walk(root).use { classFiles ->
                classFiles
                    .filter { it.toString().endsWith(".class") }
                    .forEach { classFile ->
                        val entryName = root.relativize(classFile).toString().replace('\\', '/')
                        output.putNextEntry(java.util.zip.ZipEntry(entryName))
                        java.nio.file.Files.newInputStream(classFile).use { it.copyTo(output) }
                        output.closeEntry()
                    }
            }
        }
        return target
    }

    private fun copyWithAddedPublicStaticField(classEntry: String): Path {
        val root = kotlin.io.path.createTempDirectory("catalog-abi-member-mutation")
        val target = root.resolve("mutated.jar")
        var transformed = false
        JarFile(catalogJar().toFile()).use { original ->
            java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(target)).use { output
                ->
                original.entries().asSequence().forEach { entry ->
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    original.getInputStream(entry).use { input ->
                        if (entry.name == classEntry) {
                            output.write(addPublicStaticLeak(input.readBytes()))
                            transformed = true
                        } else {
                            input.copyTo(output)
                        }
                    }
                    output.closeEntry()
                }
            }
        }
        check(transformed) { "Missing class entry to transform: $classEntry" }
        return target
    }

    private fun addPublicStaticLeak(classBytes: ByteArray): ByteArray {
        val classFile = ClassFile.of()
        val model = classFile.parse(classBytes)
        val addField =
            ClassTransform.endHandler { builder ->
                builder.withField(
                    "LEAK",
                    ConstantDescs.CD_String,
                    ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC,
                )
            }
        return classFile.transformClass(model, ClassTransform.ACCEPT_ALL.andThen(addField))
    }

    private data class ParsedClass(val entryName: String, val model: ClassModel)
}
