package org.aevix.daemon.data

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DexStreamReaderTest {
  @Test
  fun copiesUntilTheDeclaredLengthAcrossShortReads() {
    val source =
        object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) {
          override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
              super.read(buffer, offset, minOf(length, 2))
        }
    val destination = ByteBuffer.allocate(5)

    copyExactly(source, destination)

    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), destination.array())
  }

  @Test(expected = EOFException::class)
  fun rejectsTruncatedDexEntries() {
    copyExactly(ByteArrayInputStream(byteArrayOf(1, 2)), ByteBuffer.allocate(3))
  }

  @Test(expected = IOException::class)
  fun rejectsDexEntriesLongerThanTheirDeclaredSize() {
    copyExactly(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteBuffer.allocate(2))
  }
}
