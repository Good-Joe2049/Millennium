package com.millennium.app.features.steamguard.export

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class SteamMaFileReadTest {
    @Test fun acceptsExactLimitAndRejectsOversizedInput() {
        val input = byteArrayOf(1, 2, 3)
        assertArrayEquals(input, MaFileInput.readBytes(ByteArrayInputStream(input), 3))
        assertTrue(runCatching { MaFileInput.readBytes(ByteArrayInputStream(input), 2) }.isFailure)
    }

    @Test fun wipesWorkingBufferOnSuccessAndFailure() {
        var captured: ByteArray? = null
        val input = object : InputStream() {
            var done = false
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                captured = buffer
                if (done) return -1
                done = true
                buffer[offset] = 42
                return 1
            }
        }
        assertArrayEquals(byteArrayOf(42), MaFileInput.readBytes(input, 8))
        assertTrue(captured!!.all { it == 0.toByte() })
        val broken = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                captured = buffer
                buffer[offset] = 42
                throw IOException("synthetic failure")
            }
        }
        assertTrue(runCatching { MaFileInput.readBytes(broken, 8) }.isFailure)
        assertTrue(captured!!.all { it == 0.toByte() })
    }
}
