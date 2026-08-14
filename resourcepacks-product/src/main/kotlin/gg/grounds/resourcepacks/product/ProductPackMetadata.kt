package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackDefinition
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** One streaming format-88 metadata implementation shared by physical validation and ZIP output. */
internal object ProductPackMetadata {
    fun write(definition: PackDefinition, output: OutputStream) {
        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        val format = definition.format
        writer.append("{\"pack\":{\"pack_format\":")
        writer.append(format.format.toString())
        writer.append(",\"min_format\":")
        writer.append(format.range.minInclusive.toString())
        writer.append(",\"max_format\":")
        writer.append(format.range.maxInclusive.toString())
        writer.append(",\"description\":\"")
        definition.description.forEach { character ->
            when (character) {
                '"' -> writer.append("\\\"")
                '\\' -> writer.append("\\\\")
                '\b' -> writer.append("\\b")
                '\u000C' -> writer.append("\\f")
                '\n' -> writer.append("\\n")
                '\r' -> writer.append("\\r")
                '\t' -> writer.append("\\t")
                else ->
                    if (character < ' ') {
                        writer.append("\\u")
                        writer.append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        writer.append(character)
                    }
            }
        }
        writer.append("\"}}\n")
        writer.flush()
    }

    fun size(definition: PackDefinition): Long =
        CountingOutputStream().let { output ->
            write(definition, output)
            output.size
        }

    private class CountingOutputStream : OutputStream() {
        var size: Long = 0
            private set

        override fun write(value: Int) {
            size = Math.addExact(size, 1L)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            size = Math.addExact(size, length.toLong())
        }
    }
}
