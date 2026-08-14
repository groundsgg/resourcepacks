package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionDestructiveCallTest {
    @Test
    fun `compiled product classes contain no destructive filesystem calls`() {
        val classes =
            Path.of(PackSetBuilder::class.java.protectionDomain.codeSource.location.toURI())
        val forbidden =
            Files.walk(classes).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .flatMap { classFileMethodReferences(Files.readAllBytes(it)).stream() }
                    .filter { it in FORBIDDEN_METHODS }
                    .sorted()
                    .toList()
            }

        assertEquals(emptyList(), forbidden)
    }

    private fun classFileMethodReferences(bytes: ByteArray): Set<String> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xcafebabe.toInt())
        input.readUnsignedShort()
        input.readUnsignedShort()
        val pool = arrayOfNulls<Constant>(input.readUnsignedShort())
        var index = 1
        while (index < pool.size) {
            pool[index] =
                when (val tag = input.readUnsignedByte()) {
                    1 -> Constant.Utf8(input.readUTF())
                    3,
                    4 -> Constant.Other(input.readInt())
                    5,
                    6 -> {
                        val value = Constant.Other(input.readLong())
                        index++
                        value
                    }
                    7 -> Constant.Class(input.readUnsignedShort())
                    8,
                    16,
                    19,
                    20 -> Constant.Other(input.readUnsignedShort())
                    9,
                    10,
                    11 -> Constant.Reference(input.readUnsignedShort(), input.readUnsignedShort())
                    12 -> Constant.NameAndType(input.readUnsignedShort(), input.readUnsignedShort())
                    15 -> Constant.Other(input.readUnsignedByte() to input.readUnsignedShort())
                    17,
                    18 -> Constant.Other(input.readUnsignedShort() to input.readUnsignedShort())
                    else -> error("unsupported class constant tag $tag")
                }
            index++
        }
        fun utf8(constantIndex: Int) = (pool[constantIndex] as Constant.Utf8).value
        return pool.filterIsInstance<Constant.Reference>().mapTo(linkedSetOf()) { reference ->
            val owner = utf8((pool[reference.classIndex] as Constant.Class).nameIndex)
            val member = pool[reference.nameAndTypeIndex] as Constant.NameAndType
            "$owner#${utf8(member.nameIndex)}"
        }
    }

    private sealed interface Constant {
        data class Utf8(val value: String) : Constant

        data class Class(val nameIndex: Int) : Constant

        data class NameAndType(val nameIndex: Int, val descriptorIndex: Int) : Constant

        data class Reference(val classIndex: Int, val nameAndTypeIndex: Int) : Constant

        data class Other(val value: Any) : Constant
    }

    private companion object {
        val FORBIDDEN_METHODS =
            setOf(
                "java/io/File#delete",
                "java/nio/file/Files#delete",
                "java/nio/file/Files#deleteIfExists",
                "java/nio/file/Files#walk",
                "java/nio/file/Files#walkFileTree",
                "java/nio/file/SecureDirectoryStream#deleteDirectory",
                "java/nio/file/SecureDirectoryStream#deleteFile",
            )
    }
}
