package net.vaier.app

import android.content.Context
import java.io.RandomAccessFile

/** Random access over an APK, so the reader touches the tail and the block and never the whole file. */
interface ApkSource {
    val length: Long

    /** The [count] bytes at [offset], or fewer when the file ends first. */
    fun read(offset: Long, count: Int): ByteArray
}

/**
 * The Vaier host that served this APK, stamped into the APK Signing Block as pair `0x56414945`
 * ("VAIE"). Android's v2/v3 verification does not cover extra pairs, so the stamp costs the
 * signature nothing — which is what lets one download carry its own server's name.
 *
 * Nothing here trusts the bytes: any file that is not a stamped APK reads back as null.
 */
object StampedServer {

    private const val PAIR_ID = 0x56414945
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_LENGTH = 22
    private const val MAX_COMMENT = 65535
    private const val FOOTER_LENGTH = 24
    private const val MAX_PAIRS = 16 * 1024 * 1024
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** The host this build was served from, or null when it carries no stamp — a sideloaded build. */
    fun of(context: Context): String? =
        try {
            RandomAccessFile(context.applicationInfo.sourceDir, "r").use { hostIn(FileSource(it)) }
        } catch (e: Exception) {
            null
        }

    fun hostIn(source: ApkSource): String? =
        try {
            stampIn(source)
        } catch (e: Exception) {
            null
        }

    private fun stampIn(source: ApkSource): String? {
        val centralDirectory = centralDirectoryOffset(source) ?: return null
        if (centralDirectory < FOOTER_LENGTH) return null

        val footer = source.read(centralDirectory - FOOTER_LENGTH, FOOTER_LENGTH)
        if (footer.size < FOOTER_LENGTH) return null
        if (!footer.copyOfRange(8, FOOTER_LENGTH).contentEquals(MAGIC)) return null

        val blockLength = uint64(footer, 0)
        if (blockLength < FOOTER_LENGTH || blockLength > centralDirectory - 8) return null

        val pairsLength = blockLength - FOOTER_LENGTH
        if (pairsLength <= 0 || pairsLength > MAX_PAIRS) return null

        val pairs = source.read(centralDirectory - blockLength, pairsLength.toInt())
        if (pairs.size < pairsLength) return null
        return hostAmong(pairs)
    }

    private fun hostAmong(pairs: ByteArray): String? {
        var at = 0
        while (at + 12 <= pairs.size) {
            val length = uint64(pairs, at)
            if (length < 4 || length > pairs.size - at - 8) return null
            if (uint32(pairs, at + 8) == PAIR_ID) {
                val value = pairs.copyOfRange(at + 12, at + 8 + length.toInt())
                return String(value, Charsets.UTF_8).trim().ifEmpty { null }
            }
            at += 8 + length.toInt()
        }
        return null
    }

    /** Walks back over the ZIP comment to the end-of-central-directory record. */
    private fun centralDirectoryOffset(source: ApkSource): Long? {
        val length = source.length
        if (length < EOCD_LENGTH) return null

        val tail = source.read(
            maxOf(0, length - (MAX_COMMENT + EOCD_LENGTH)),
            minOf(length, (MAX_COMMENT + EOCD_LENGTH).toLong()).toInt(),
        )
        for (at in tail.size - EOCD_LENGTH downTo 0) {
            if (uint32(tail, at) != EOCD_SIGNATURE) continue
            if (at + EOCD_LENGTH + uint16(tail, at + 20) != tail.size) continue
            return uint32(tail, at + 16).toLong() and 0xFFFFFFFFL
        }
        return null
    }

    private fun uint16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun uint32(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    /** Signed, so a length with its top bit set falls out of every bounds check below. */
    private fun uint64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private class FileSource(private val file: RandomAccessFile) : ApkSource {

        override val length: Long get() = file.length()

        override fun read(offset: Long, count: Int): ByteArray {
            file.seek(offset)
            val bytes = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = file.read(bytes, filled, count - filled)
                if (read < 0) break
                filled += read
            }
            return if (filled == count) bytes else bytes.copyOf(filled)
        }
    }
}
