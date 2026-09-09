package net.vaier.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A miniature APK, built rather than checked in: a real ZIP with an APK Signing Block spliced in ahead of
 * its central directory, exactly as {@code apksigner} leaves one. Small enough to assert on byte by byte,
 * and honest enough that {@link java.util.zip.ZipFile} opens it.
 *
 * <p>Shared by the stamping tests and the adapter's, so the two never drift into testing different file
 * formats. Only the <em>writer</em> lives here — each test reads the result with its own eyes.
 */
public final class SyntheticApk {

    public static final String ENTRY_NAME = "classes.dex";
    public static final String ENTRY_CONTENT = "not really dalvik";

    private static final int V2_SIGNATURE_ID = 0x7109871a;
    private static final int VERITY_PADDING_ID = 0x42726577;
    private static final int CD_ALIGNMENT = 4096;

    private SyntheticApk() {
    }

    /** A signed package whose verity padding holds the central directory on a 4096-byte boundary. */
    public static byte[] signed() {
        return withSigningBlock(true);
    }

    /** A signed package with no padding pair, so nobody is holding an alignment. */
    public static byte[] signedWithoutVerityPadding() {
        return withSigningBlock(false);
    }

    /** A v1-only package: a plain ZIP with no signing block at all. */
    public static byte[] v1Only() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry(ENTRY_NAME));
            zip.write(ENTRY_CONTENT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("cannot build the fixture", e);
        }
        return raw.toByteArray();
    }

    private static byte[] withSigningBlock(boolean withVerityPadding) {
        byte[] zip = v1Only();
        int cdOffset = centralDirectoryOffset(zip);

        List<byte[]> pairs = new ArrayList<>();
        pairs.add(pair(V2_SIGNATURE_ID, new byte[48]));
        if (withVerityPadding) {
            int withoutPadding = cdOffset + 8 + pairs.get(0).length + 24;
            pairs.add(pair(VERITY_PADDING_ID, new byte[Math.floorMod(-(withoutPadding + 12), CD_ALIGNMENT)]));
        }

        long size = pairs.stream().mapToInt(pair -> pair.length).sum() + 24L;
        ByteBuffer block = ByteBuffer.allocate((int) (8 + size)).order(ByteOrder.LITTLE_ENDIAN);
        block.putLong(size);
        pairs.forEach(block::put);
        block.putLong(size);
        block.put("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));

        byte[] out = new byte[zip.length + block.capacity()];
        System.arraycopy(zip, 0, out, 0, cdOffset);
        System.arraycopy(block.array(), 0, out, cdOffset, block.capacity());
        System.arraycopy(zip, cdOffset, out, cdOffset + block.capacity(), zip.length - cdOffset);
        ByteBuffer.wrap(out, endOfCentralDirectory(out) + 16, 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cdOffset + block.capacity());
        return out;
    }

    private static byte[] pair(int id, byte[] value) {
        return ByteBuffer.allocate(12 + value.length).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(4L + value.length).putInt(id).put(value).array();
    }

    private static int centralDirectoryOffset(byte[] apk) {
        return ByteBuffer.wrap(apk, endOfCentralDirectory(apk) + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int endOfCentralDirectory(byte[] apk) {
        for (int at = apk.length - 22; at >= 0; at--) {
            if ((ByteBuffer.wrap(apk, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL) == 0x06054b50L) {
                return at;
            }
        }
        throw new IllegalStateException("not a zip");
    }
}
