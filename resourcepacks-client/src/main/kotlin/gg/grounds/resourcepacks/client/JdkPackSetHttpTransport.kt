package gg.grounds.resourcepacks.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JdkPackSetHttpTransport(
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
) : PackSetHttpTransport {
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
