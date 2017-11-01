package io.ktor.client.utils

import io.ktor.network.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import java.io.*


fun InputStream.toByteReadChannel(): ByteReadChannel = writer(ioCoroutineDispatcher, ByteChannel()) {
    var count = 0
    val writeBlock = { buffer: ByteBuffer ->
        count = read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
        if (count > 0) {
            buffer.position(buffer.position() + count)
        }
    }

    use {
        while (true) {
            count = 0
            channel.write(block = writeBlock)
            if (count < 0) {
                channel.close()
                break
            }
        }
    }
}.channel

private class ByteReadChannelInputStreamAdapter(private val channel: ByteReadChannel) : InputStream() {
    override fun read(): Int = runBlocking(Unconfined) {
        channel.readByte().toInt() and 0xff
    }

    override fun read(array: ByteArray, offset: Int, length: Int): Int = runBlocking(Unconfined) {
        channel.readAvailable(array, offset, length)
    }
}

fun ByteReadChannel.toInputStream(): InputStream = ByteReadChannelInputStreamAdapter(this)

