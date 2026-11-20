package org.aevix.daemon.data

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Copy exactly the expected DEX bytes, rejecting truncation and trailing data. */
internal fun copyExactly(inputStream: InputStream, destination: ByteBuffer) {
  val scratch = ByteArray(minOf(8192, destination.remaining()))
  while (destination.hasRemaining()) {
    val count = inputStream.read(scratch, 0, minOf(scratch.size, destination.remaining()))
    when {
      count < 0 -> throw EOFException("DEX entry ended before its declared size")
      count == 0 -> {
        val value = inputStream.read()
        if (value < 0) throw EOFException("DEX entry ended before its declared size")
        destination.put(value.toByte())
      }
      else -> destination.put(scratch, 0, count)
    }
  }
  if (inputStream.read() != -1) {
    throw IOException("DEX entry contains more bytes than its declared size")
  }
}

