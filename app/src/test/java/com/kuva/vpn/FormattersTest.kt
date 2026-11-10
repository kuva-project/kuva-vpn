package com.kuva.vpn

import com.kuva.vpn.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test fun `bytes formatting`() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.0 MB", Formatters.bytes(1L shl 20))
        assertEquals("1.00 GB", Formatters.bytes(1L shl 30))
    }
    @Test fun `duration formatting`() {
        assertEquals("00:20", Formatters.duration(20_000))
        assertEquals("01:05", Formatters.duration(65_000))
        assertEquals("01:00:00", Formatters.duration(3_600_000))
    }
    @Test fun `ping formatting`() {
        assertEquals("—", Formatters.ping(null))
        assertEquals("timeout", Formatters.ping(-1))
        assertEquals("42 ms", Formatters.ping(42))
    }
}
