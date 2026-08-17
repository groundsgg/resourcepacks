package gg.grounds.resourcepacks.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JdkPackSetHttpTransport private constructor(private val client: HttpClient) :
    PackSetHttpTransport {
    constructor() : this(Duration.ofSeconds(5))

    constructor(
        connectTimeout: Duration
    ) : this(
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    )

    internal constructor(
        client: HttpClient,
        testOnly: Boolean,
    ) : this(
        client.also {
            require(it.followRedirects() == HttpClient.Redirect.NEVER) {
                "HTTP client must reject redirects."
            }
        }
    )

    override fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse {
        val request =
            HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return PackSetHttpResponse(
            response.statusCode(),
            response.headers().firstValue("ETag").orElse(null),
            response.body(),
        )
    }
}
