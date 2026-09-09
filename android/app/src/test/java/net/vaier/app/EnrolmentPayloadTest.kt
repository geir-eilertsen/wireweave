package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EnrolmentPayloadTest {

    private val ourPublicKey = "eA1uZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTiE="
    private val ourPrivateKey = "cM1hZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTkA="

    private fun conf(publicKey: String = ourPublicKey) = """
        # VAIER: {"peerType":"MOBILE_CLIENT","name":"Pixel 8","publicKey":"$publicKey","id":"9f2c"}
        [Interface]
        Address = 10.13.13.7/32
        DNS = 172.20.0.53
        [Peer]
        PublicKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=
        PresharedKey = FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE=
        Endpoint = vaier.example.com:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    private fun encodedOf(text: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun parse(text: String) = EnrolmentPayload.parse(encodedOf(text), ourPublicKey, ourPrivateKey)

    @Test
    fun `the approval carries what the home screen shows`() {
        val enrolment = parse(conf())

        assertEquals("9f2c", enrolment.peerId)
        assertEquals("Pixel 8", enrolment.peerName)
        assertEquals("MOBILE_CLIENT", enrolment.peerType)
        assertEquals("10.13.13.7/32", enrolment.tunnelAddress)
    }

    @Test
    fun `our private key is inserted into the interface Vaier left open`() {
        val lines = parse(conf()).configText.lines()
        val interfaceAt = lines.indexOf("[Interface]")

        assertEquals("PrivateKey = $ourPrivateKey", lines[interfaceAt + 1])
        assertEquals(1, lines.count { it.startsWith("PrivateKey") })
    }

    @Test
    fun `everything Vaier sent survives the round trip`() {
        val configText = parse(conf()).configText

        assertTrue(configText.contains("Endpoint = vaier.example.com:51820"))
        assertTrue(configText.contains("PresharedKey = FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="))
        assertTrue(configText.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(configText.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `base64url is decoded unpadded, with its own alphabet`() {
        // Base64 of pure ASCII can never produce '-' or '_', so an ordinary name would let a plain
        // base64 decoder pass this test. These two characters (UTF-8 c3 bf e7 bf bf) put the
        // sextets 62 and 63 into the encoding, where only the URL-safe alphabet spells them.
        val name = "Pixel 8 \u00FF\u7FFF"
        val encoded = encodedOf(conf().replace("Pixel 8", name))

        assertTrue(encoded, encoded.contains('-'))
        assertTrue(encoded, encoded.contains('_'))
        assertFalse(encoded.endsWith("="))
        assertEquals(name, EnrolmentPayload.parse(encoded, ourPublicKey, ourPrivateKey).peerName)
    }

    @Test
    fun `an approval minted for another key is refused`() {
        val other = "AAAAZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTiE="

        val refusal = refusalFrom { parse(conf(publicKey = other)) }

        assertTrue(refusal, refusal.contains("different device"))
    }

    @Test
    fun `an approval carrying a private key is refused, because Vaier must never send one`() {
        val withPrivateKey = conf().replace("[Interface]", "[Interface]\nPrivateKey = $ourPrivateKey")

        val refusal = refusalFrom { parse(withPrivateKey) }

        assertTrue(refusal, refusal.contains("private key"))
    }

    @Test
    fun `an answer with no VAIER comment is refused`() {
        val refusal = refusalFrom { parse(conf().lines().drop(1).joinToString("\n")) }

        assertTrue(refusal, refusal.contains("not from Vaier"))
    }

    @Test
    fun `an answer with no interface section is refused`() {
        val refusal = refusalFrom { parse(conf().replace("[Interface]", "[Nonsense]")) }

        assertTrue(refusal, refusal.contains("Interface"))
    }

    @Test
    fun `data that is not base64 at all is refused`() {
        val refusal = refusalFrom { EnrolmentPayload.parse("not base 64 !!", ourPublicKey, ourPrivateKey) }

        assertTrue(refusal, refusal.contains("damaged"))
    }

    @Test
    fun `an empty approval is refused`() {
        val refusal = refusalFrom { EnrolmentPayload.parse("", ourPublicKey, ourPrivateKey) }

        assertTrue(refusal, refusal.isNotEmpty())
    }

    @Test
    fun `the shared secret that leaving needs is read back out of the configuration`() {
        val configText = parse(conf()).configText

        assertEquals(
            "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE=",
            EnrolmentPayload.presharedKeyIn(configText),
        )
    }

    @Test
    fun `a configuration with no shared secret reads back as nothing`() {
        assertEquals("", EnrolmentPayload.presharedKeyIn("[Interface]\nAddress = 10.13.13.7/32"))
    }

    private fun refusalFrom(block: () -> Unit): String =
        try {
            block()
            throw AssertionError("expected the enrolment to be refused")
        } catch (e: EnrolmentException) {
            e.message.orEmpty()
        }
}
