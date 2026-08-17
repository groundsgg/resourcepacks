package gg.grounds.resourcepacks.client

import java.io.InputStream
import java.net.URI
import java.time.Duration

interface PackSetHttpTransport {
    fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse
}

data class PackSetHttpResponse(val status: Int, val etag: String?, val body: InputStream) :
    AutoCloseable {
    override fun close() = body.close()
}
