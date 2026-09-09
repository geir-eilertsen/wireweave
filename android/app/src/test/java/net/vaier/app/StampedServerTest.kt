package net.vaier.app

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StampedServerTest {

    @Test
    fun `the stamped host comes back`() {
        val apk = apk(pairs = listOf(DUMMY_PAIR, vaie("vaier.eilertsen.family")))

        assertEquals("vaier.eilertsen.family", StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `the stamp is found behind a ZIP comment`() {
        val apk = apk(pairs = listOf(vaie("vaier.example.com")), comment = "served by Vaier".toByteArray())

        assertEquals("vaier.example.com", StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `the stamp is found however many pairs sit in front of it`() {
        val apk = apk(pairs = listOf(DUMMY_PAIR, DUMMY_PAIR, DUMMY_PAIR, vaie("vaier.example.com")))

        assertEquals("vaier.example.com", StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `an unstamped APK has no host`() {
        val apk = apk(pairs = listOf(DUMMY_PAIR))

        assertNull(StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `an APK with no signing block at all has no host`() {
        val apk = apk(pairs = emptyList(), signingBlock = false)

        assertNull(StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `a block whose magic is wrong is not read`() {
        val apk = apk(pairs = listOf(vaie("vaier.example.com")), magic = "APK Sig Block 43".toByteArray())

        assertNull(StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `a truncated file has no host`() {
        val apk = apk(pairs = listOf(vaie("vaier.example.com")))

        assertNull(StampedServer.hostIn(bytes(apk.copyOf(apk.size / 2))))
        assertNull(StampedServer.hostIn(bytes(ByteArray(0))))
        assertNull(StampedServer.hostIn(bytes("not an APK at all".toByteArray())))
    }

    @Test
    fun `a pair claiming to run past the block is refused rather than thrown`() {
        val apk = apk(pairs = listOf(DUMMY_PAIR, vaie("vaier.example.com")), pairLengthBias = 4096)

        assertNull(StampedServer.hostIn(bytes(apk)))
    }

    @Test
    fun `an empty stamp is no stamp`() {
        val apk = apk(pairs = listOf(vaie("")))

        assertNull(StampedServer.hostIn(bytes(apk)))
    }

    // A synthetic APK: one stored entry, a signing block, a central directory and an EOCD.

    private fun bytes(apk: ByteArray) = object : ApkSource {
        override val length = apk.size.toLong()
        override fun read(offset: Long, count: Int): ByteArray {
            if (offset < 0 || offset > apk.size) return ByteArray(0)
            val from = offset.toInt()
            return apk.copyOfRange(from, minOf(from + count, apk.size))
        }
    }

    private fun vaie(host: String) = 0x56414945 to host.toByteArray(Charsets.UTF_8)

    private fun apk(
        pairs: List<Pair<Int, ByteArray>>,
        comment: ByteArray = ByteArray(0),
        magic: ByteArray = MAGIC,
        signingBlock: Boolean = true,
        pairLengthBias: Int = 0,
    ): ByteArray {
        val name = "classes.dex".toByteArray()
        val data = "dex".toByteArray()

        val out = ByteArrayOutputStream()
        out.u32(0x04034b50); out.u16(20); out.u16(0); out.u16(0); out.u16(0); out.u16(0)
        out.u32(0); out.u32(data.size); out.u32(data.size); out.u16(name.size); out.u16(0)
        out.write(name); out.write(data)

        if (signingBlock) out.write(signingBlock(pairs, magic, pairLengthBias))
        val cdOffset = out.size()

        val cd = ByteArrayOutputStream()
        cd.u32(0x02014b50); cd.u16(20); cd.u16(20); cd.u16(0); cd.u16(0); cd.u16(0); cd.u16(0)
        cd.u32(0); cd.u32(data.size); cd.u32(data.size)
        cd.u16(name.size); cd.u16(0); cd.u16(0); cd.u16(0); cd.u16(0); cd.u32(0); cd.u32(0)
        cd.write(name)
        out.write(cd.toByteArray())

        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(1); out.u16(1)
        out.u32(cd.size()); out.u32(cdOffset); out.u16(comment.size); out.write(comment)
        return out.toByteArray()
    }

    private fun signingBlock(pairs: List<Pair<Int, ByteArray>>, magic: ByteArray, bias: Int): ByteArray {
        val body = ByteArrayOutputStream()
        pairs.forEach { (id, value) ->
            body.u64(value.size + 4L + bias)
            body.u32(id)
            body.write(value)
        }
        val sizeA = body.size() + 8L + magic.size
        val block = ByteArrayOutputStream()
        block.u64(sizeA)
        block.write(body.toByteArray())
        block.u64(sizeA)
        block.write(magic)
        return block.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) = repeat(2) { write((value shr (8 * it)) and 0xFF) }
    private fun ByteArrayOutputStream.u32(value: Int) = repeat(4) { write((value shr (8 * it)) and 0xFF) }
    private fun ByteArrayOutputStream.u64(value: Long) = repeat(8) { write(((value shr (8 * it)) and 0xFFL).toInt()) }

    private companion object {
        val MAGIC = "APK Sig Block 42".toByteArray()
        val DUMMY_PAIR = 0x7109871a to ByteArray(64) { it.toByte() }
    }
}
