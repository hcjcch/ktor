package io.ktor.client.backend.jetty

import io.ktor.client.*
import io.ktor.client.backend.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.client.utils.*
import io.ktor.network.util.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.io.pool.*
import org.eclipse.jetty.http.*
import org.eclipse.jetty.http2.api.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.http2.frames.*
import org.eclipse.jetty.util.ssl.*
import java.net.*
import java.util.*

private val DEFAULT_RESPONSE_SIZE = 8192
private val DEFAULT_RESPONSE_POOL_SIZE = 8192

class JettyHttp2Backend : HttpClientBackend {
    private val sslContextFactory = SslContextFactory(true)

    private val responsePool = object : DefaultPool<ByteBuffer>(DEFAULT_RESPONSE_POOL_SIZE) {
        override fun produceInstance(): ByteBuffer = ByteBuffer.allocate(DEFAULT_RESPONSE_SIZE)!!
    }

    private val jettyClient = HTTP2Client().apply {
        addBean(sslContextFactory)
    }

    init {
        jettyClient.start()
    }

    suspend override fun makeRequest(request: HttpRequest): HttpResponseBuilder {
        val requestTime = Date()
        val session = connect(request.host, request.port).apply {
            this.settings(SettingsFrame(emptyMap(), true), org.eclipse.jetty.util.Callback.NOOP)
        }

        val headersFrame = prepareHeadersFrame(request)

        val response = Http2ResponseChannel()
        val requestChannel = withPromise<Stream> { promise ->
            session.newStream(headersFrame, promise, response.listener)
        }.let { Http2Request(it) }

        sendRequestBody(requestChannel, request.body)

        val result = HttpResponseBuilder()
        response.awaitHeaders().let {
            result.status = it.statusCode
            result.headers.appendAll(it.headers)
        }

        val bodyChannel = writer(ioCoroutineDispatcher) {
            val buffer = responsePool.borrow()
            while (true) {
                buffer.clear()
                val count = response.read(buffer)
                if (count < 0) break

                buffer.flip()
                channel.writeFully(buffer)
            }

            responsePool.recycle(buffer)
            channel.close()
        }.channel

        with(result) {
            this.requestTime = requestTime
            responseTime = Date()

            version = HttpProtocolVersion.HTTP_2_0
            body = ByteReadChannelBody(bodyChannel)
            origin = response
        }

        return result
    }

    private suspend fun connect(host: String, port: Int): Session {
        return withPromise { promise ->
            jettyClient.connect(sslContextFactory, InetSocketAddress(host, port), Session.Listener.Adapter(), promise)
        }
    }

    private fun prepareHeadersFrame(request: HttpRequest): HeadersFrame {
        val headers = HttpFields()

        request.headers.flattenEntries().forEach { (name, value) ->
            headers.add(name, value)
        }

        val meta = MetaData.Request(
                request.method.value,
                request.url.scheme,
                HostPortHttpField("${request.url.host}:${request.url.port}"),
                request.url.fullPath,
                HttpVersion.HTTP_2,
                headers,
                Long.MIN_VALUE
        )

        return HeadersFrame(meta, null, request.body is EmptyBody)
    }

    private suspend fun sendRequestBody(requestChannel: Http2Request, body: Any) {
        if (body is Unit || body is EmptyBody) return
        if (body !is HttpMessageBody) error("Wrong payload type: $body, expected HttpMessageBody")

        val sourceChannel = body.toByteReadChannel()
        launch(ioCoroutineDispatcher) {
            while (!sourceChannel.isClosedForRead) {
                sourceChannel.read { requestChannel.write(it) }
            }

            requestChannel.endBody()
        }
    }

    override fun close() {
        jettyClient.stop()
    }

    companion object : HttpClientBackendFactory {
        override operator fun invoke(block: HttpClientBackendConfig.() -> Unit): HttpClientBackend = JettyHttp2Backend()
    }
}