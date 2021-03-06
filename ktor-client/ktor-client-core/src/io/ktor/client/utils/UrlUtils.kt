package io.ktor.client.utils

import io.ktor.client.request.*
import io.ktor.http.*
import java.net.*


fun HttpRequestBuilder.url(
        scheme: String = "http",
        host: String = "localhost",
        port: Int = 80,
        path: String = ""
) {
    url.apply {
        this.scheme = scheme
        this.host = host
        this.port = port
        this.path = path
    }
}

fun HttpRequestBuilder.url(data: Url) {
    url.takeFrom(data)
}

fun UrlBuilder.takeFrom(url: Url): UrlBuilder {
    scheme = url.scheme
    host = url.host
    port = url.port
    path = url.path
    username = url.username
    password = url.password
    fragment = url.fragment
    url.queryParameters?.let { addQueryParameters(it) }

    return this
}

fun UrlBuilder.takeFrom(uri: URI) {
    scheme = uri.scheme
    host = uri.host
    path = uri.path
    port = uri.port.takeIf { it > 0 } ?: if (scheme == "https") 443 else 80
    uri.query?.let { addQueryParameters(parseQueryString(it)) }
    fragment = uri.fragment
}

fun UrlBuilder.takeFrom(url: URL) = takeFrom(url.toURI())

fun UrlBuilder.takeFrom(url: String) = takeFrom(URI(url))

fun UrlBuilder.takeFrom(url: UrlBuilder): UrlBuilder {
    scheme = url.scheme
    host = url.host
    port = url.port
    path = url.path
    username = url.username
    password = url.password
    url.queryParameters?.let { addQueryParameters(it) }
    fragment = url.fragment

    return this
}

val Url.fullPath: String
    get() {
        val parameters = when {
            queryParameters == null -> ""
            queryParameters.isEmpty() -> "?"
            else -> "?${decodeURLPart(queryParameters.formUrlEncode())}"
        }

        return "$path$parameters"
    }

val Url.hostWithPort: String get() = "$host:$port"