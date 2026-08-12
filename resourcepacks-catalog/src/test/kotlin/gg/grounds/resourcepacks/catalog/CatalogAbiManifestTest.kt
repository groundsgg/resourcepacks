package gg.grounds.resourcepacks.catalog

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogAbiManifestTest {
    @Test
    fun `catalog JAR effective public ABI matches the checked in manifest`() {
        assertEquals(expectedManifest(), effectivePublicAbi(catalogClasses()))
    }

    @Test
    fun `ABI gate rejects a controlled unexpected public owner and member`() {
        val unexpectedOwner = assertFailsWith<AssertionError> {
            assertEquals(expectedManifest(), effectivePublicAbi(catalogClasses() + String::class.java))
        }
        assertContains(unexpectedOwner.message.orEmpty(), "java.lang.String")

        val unexpectedMember = assertFailsWith<AssertionError> {
            assertEquals(expectedManifest(), effectivePublicAbi(catalogClasses(), extraMembers = setOf("controlled.Public|METHOD|leak()|java.lang.String")))
        }
        assertContains(unexpectedMember.message.orEmpty(), "controlled.Public|METHOD|leak()|java.lang.String")
    }

    private fun expectedManifest(): Set<String> =
        requireNotNull(javaClass.classLoader.getResource("catalog-public-api.txt"))
            .readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .toSet()

    private fun catalogClasses(): Set<Class<*>> =
        setOf(
            CatalogBuildInfo::class.java,
            GroundsGuiIds::class.java,
            GroundsGuiTheme::class.java,
            GroundsAssets::class.java,
            GroundsAssetCatalog::class.java,
        )

    private fun effectivePublicAbi(
        classes: Set<Class<*>>,
        extraMembers: Set<String> = emptySet(),
    ): Set<String> =
        classes.flatMapTo(sortedSetOf()) { type ->
            buildSet {
                if (Modifier.isPublic(type.modifiers)) {
                    type.fields
                        .filter { Modifier.isPublic(it.modifiers) }
                        .forEach { add("${type.name}|FIELD|${it.name}|${it.type.name}") }
                    type.methods
                        .filter { Modifier.isPublic(it.modifiers) && it.declaringClass == type }
                        .forEach { method ->
                            add("${type.name}|METHOD|${method.name}(${method.parameterTypes.joinToString(",") { it.name }})|${method.returnType.name}")
                        }
                    type.constructors
                        .filter { Modifier.isPublic(it.modifiers) }
                        .forEach { constructor ->
                            add("${type.name}|CONSTRUCTOR|<init>(${constructor.parameterTypes.joinToString(",") { it.name }})|void")
                        }
                }
            }
        } + extraMembers
}
