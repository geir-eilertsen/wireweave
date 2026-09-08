package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaierAddressTest {

    @Test
    fun `a bare host is already normal`() {
        assertEquals("vaier.example.com", VaierAddress.normalise("vaier.example.com"))
    }

    @Test
    fun `case and surrounding whitespace do not matter`() {
        assertEquals("vaier.example.com", VaierAddress.normalise("  Vaier.Example.COM \n"))
    }

    @Test
    fun `whitespace typed inside the address is dropped`() {
        assertEquals("vaier.example.com", VaierAddress.normalise("vaier. example .com"))
    }

    @Test
    fun `a pasted URL keeps only its host`() {
        assertEquals("vaier.example.com", VaierAddress.normalise("https://vaier.example.com/explorer.html?a=b"))
        assertEquals("vaier.example.com", VaierAddress.normalise("HTTP://Vaier.Example.com/"))
    }

    @Test
    fun `a port survives, because the address has to reach the server`() {
        assertEquals("vaier.example.com:8443", VaierAddress.normalise("https://vaier.example.com:8443/"))
    }

    @Test
    fun `a single label is a legitimate host on a LAN`() {
        assertEquals("vaier", VaierAddress.normalise("vaier"))
    }

    @Test
    fun `nothing usable comes back as nothing`() {
        assertNull(VaierAddress.normalise(""))
        assertNull(VaierAddress.normalise("   "))
        assertNull(VaierAddress.normalise("https://"))
        assertNull(VaierAddress.normalise("vaier example!com"))
        assertNull(VaierAddress.normalise("-vaier.example.com"))
    }
}
