package com.example.witches.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PrototypeCodecTest {
    @Test
    fun everySettingsCombinationAndCardRoundTrips() {
        for (flags in 0..15) {
            for (id in -1..8) {
              for (volume in listOf(0f, .35f, 1f)) {
                val expected = PrototypeRecord(
                    AppSettings(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, volume),
                    id.takeUnless { it == -1 },
                )
                assertEquals(expected, decode(PrototypeCodec.encode(expected)))
              }
            }
        }
    }

    @Test
    fun truncatedRecordsAreRejected() {
        val valid = PrototypeCodec.encode(PrototypeRecord())
        for (length in 0 until valid.size) {
            assertThrows(IOException::class.java) { decode(valid.copyOf(length)) }
        }
    }

    @Test
    fun changedBytesAndTrailingDataAreRejected() {
        val valid = PrototypeCodec.encode(PrototypeRecord())
        valid.indices.forEach { index ->
            val damaged = valid.copyOf()
            damaged[index] = (damaged[index].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { decode(damaged) }
        }
        assertThrows(IOException::class.java) { decode(valid + 0.toByte()) }
    }

    @Test
    fun aFutureVersionIsReportedSeparately() {
        val future = PrototypeCodec.encode(PrototypeRecord())
        ByteBuffer.wrap(future).putInt(4, 99)
        updateChecksum(future)
        assertThrows(UnsupportedSchemaException::class.java) { decode(future) }
    }

    @Test
    fun aStorageReadFailureIsNotReportedAsCorruption() {
        val failure = IOException("Носитель недоступен")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
        }
        assertSame(failure, assertThrows(IOException::class.java) { PrototypeCodec.decode(input) })
    }

    @Test
    fun unknownFormatFlagsAndCardIdsAreRejected() {
        listOf(0 to 0, 8 to 16, 9 to 9, 9 to -2).forEach { (index, value) ->
            val invalid = PrototypeCodec.encode(PrototypeRecord())
            invalid[index] = value.toByte()
            updateChecksum(invalid)
            assertThrows(IOException::class.java) { decode(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeCodec.encode(PrototypeRecord(selectedCardId = 9))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeCodec.encode(PrototypeRecord(selectedCardId = -1))
        }
    }

    private fun decode(bytes: ByteArray) = PrototypeCodec.decode(ByteArrayInputStream(bytes))

    private fun updateChecksum(bytes: ByteArray) {
        val checksum = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
        ByteBuffer.wrap(bytes).putInt(bytes.size - 4, checksum)
    }
}
