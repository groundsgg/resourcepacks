package gg.grounds.resourcepacks.client

import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration

/**
 * Strict in-memory HTTP boundary used to prove resolver request behavior without network access.
 */
internal class LoopbackPackSetServer(private val responses: Map<URI, PackSetHttpResponse>) :
    PackSetHttpTransport {
    val requests = mutableListOf<Request>()

    override fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse {
        require(
            uri.path.endsWith("/channels/stable.json") ||
                uri.path.endsWith("/channels/edge.json") ||
                uri.path.endsWith("/manifest.json")
        ) {
            "Unexpected request path."
        }
        requests += Request(uri, ifNoneMatch, timeout)
        return responses[uri] ?: error("Missing response.")
    }

    data class Request(val uri: URI, val ifNoneMatch: String?, val timeout: Duration)

    companion object {
        fun response(status: Int, etag: String? = null, body: ByteArray = byteArrayOf()) =
            PackSetHttpResponse(status, etag, ByteArrayInputStream(body))
    }
}
