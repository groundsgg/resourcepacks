package gg.grounds.resourcepacks.client

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object BoundedResponseReader {
    fun read(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 65_536))
        val buffer = ByteArray(65_536)
        var count = 0
        val limitPlusOne = Math.addExact(limit, 1)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, limitPlusOne - count))
            if (read < 0) return output.toByteArray()
            count = Math.addExact(count, read)
            if (count > limit) throw IllegalArgumentException("Response exceeds configured limit.")
            output.write(buffer, 0, read)
        }
    }
}
