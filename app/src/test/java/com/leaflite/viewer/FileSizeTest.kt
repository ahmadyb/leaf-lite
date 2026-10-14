package com.leaflite.viewer

import com.leaflite.viewer.util.FileSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeTest {

    @Test
    fun `zero and tiny files`() {
        assertEquals("0 B", FileSize.format(0L))
        assertEquals("0 B", FileSize.format(-12L))
        assertEquals("812 B", FileSize.format(812L))
    }

    @Test
    fun `kilo and mega bytes`() {
        assertEquals("1 KB", FileSize.format(1024L))
        assertEquals("340 KB", FileSize.format(348_160L))
        assertEquals("2.4 MB", FileSize.format(2_516_582L))
        assertEquals("5.2 MB", FileSize.format(5_452_595L))
    }

    @Test
    fun `giga bytes have no decimals`() {
        assertEquals("3 GB", FileSize.format(3L * 1024L * 1024L * 1024L))
    }
}
