package com.github.ghactions.api

import java.net.Authenticator
import java.net.ProxySelector
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

/**
 * @param proxySelector 代理选择器；null 表示直连。
 *   由调用方（IDE 层）根据用户的配置决定——本类刻意不认识 IntelliJ，
 *   否则整个 api 包就无法用纯 JVM 测试覆盖了。
 * @param authenticator 代理认证凭据；null 表示不需要认证。
 */
class JdkHttpTransport(
    proxySelector: ProxySelector? = null,
    authenticator: Authenticator? = null,
) : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply {
            proxySelector?.let { proxy(it) }
            authenticator?.let { authenticator(it) }
        }
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
