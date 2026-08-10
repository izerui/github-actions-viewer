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

class JdkHttpTransport : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        // 走 IDE 配置的代理。IntelliJ 启动时会把自己的代理选择器装成 JVM 默认，
        // 但 JDK 的 HttpClient 默认并不使用它——不显式指定就是直连，用户在
        // Settings 里配的代理形同虚设。有些网络环境只能经代理访问 GitHub，
        // 不遵从这项配置等于完全不可用。
        .apply {
            ProxySelector.getDefault()?.let { proxy(it) }
            // 代理需要认证时，凭据同样由 IDE 管理
            Authenticator.getDefault()?.let { authenticator(it) }
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
