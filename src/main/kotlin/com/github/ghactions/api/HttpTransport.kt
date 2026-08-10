package com.github.ghactions.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    /** HTTP 头名大小写不固定，此处做大小写不敏感查询。 */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class JdkHttpTransport : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), BodyHandlers.ofString())
        val flatHeaders = response.headers().map()
            .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

        return HttpResponse(response.statusCode(), response.body().orEmpty(), flatHeaders)
    }
}
