package gg.grounds.resourcepacks.catalog

import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogAbiManifestTest {
    @Test
    fun `final catalog JAR public ABI exactly matches manifest`() {
        assertEquals(expectedManifest(), effectivePublicAbi(catalogJar()))
    }

    @Test
    fun `ABI gate rejects a real JAR mutation containing another public owner`() {
        val mutated = copyWithExtraClass("injected.ExtraOwner", "public final class ExtraOwner { public String leak() { return \"x\"; } }")
        assertFailsWith<AssertionError> { assertEquals(expectedManifest(), effectivePublicAbi(mutated)) }
    }

    @Test
    fun `ABI gate rejects a real JAR mutation containing another public member`() {
        val mutated = copyWithExtraClass("injected.ExtraMember", "public final class ExtraMember { public static final String LEAK = \"x\"; }")
        assertFailsWith<AssertionError> { assertEquals(expectedManifest(), effectivePublicAbi(mutated)) }
    }

    private fun expectedManifest() =
        requireNotNull(javaClass.classLoader.getResource("catalog-public-api.txt")).readText().lineSequence().filter(String::isNotBlank).toSet()

    private fun catalogJar() = Path.of(System.getProperty("catalog.jar"))

    private fun effectivePublicAbi(jar: Path): Set<String> =
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
            JarFile(jar.toFile()).use { archive ->
                archive.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name.removeSuffix(".class").replace('/', '.') }
                    .flatMap { name -> abiOf(Class.forName(name, false, loader)).asSequence() }.toSet()
            }
        }

    private fun abiOf(type: Class<*>): Set<String> {
        if (!Modifier.isPublic(type.modifiers) && !Modifier.isProtected(type.modifiers)) return emptySet()
        return buildSet {
            type.fields.filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }.forEach { add("${type.name}|FIELD|${it.name}|${it.type.name}") }
            type.methods.filter { it.declaringClass == type && (Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)) }.forEach { add("${type.name}|METHOD|${it.name}(${it.parameterTypes.joinToString(",") { p -> p.name }})|${it.returnType.name}") }
            type.constructors.filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }.forEach { add("${type.name}|CONSTRUCTOR|<init>(${it.parameterTypes.joinToString(",") { p -> p.name }})|void") }
        }
    }

    private fun copyWithExtraClass(name: String, source: String): Path {
        val root = kotlin.io.path.createTempDirectory("catalog-abi-mutation")
        val sourceFile = root.resolve(name.replace('.', '/') + ".java")
        java.nio.file.Files.createDirectories(sourceFile.parent)
        java.nio.file.Files.writeString(sourceFile, "package ${name.substringBeforeLast('.')}; $source")
        check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), sourceFile.toString()) == 0)
        val target = root.resolve("mutated.jar")
        java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(target)).use { output ->
            JarFile(catalogJar().toFile()).use { original -> original.entries().asSequence().forEach { entry -> output.putNextEntry(java.util.zip.ZipEntry(entry.name)); original.getInputStream(entry).copyTo(output); output.closeEntry() } }
            val classFile = root.resolve(name.replace('.', '/') + ".class")
            output.putNextEntry(java.util.zip.ZipEntry(name.replace('.', '/') + ".class")); java.nio.file.Files.newInputStream(classFile).copyTo(output); output.closeEntry()
        }
        return target
    }
}
