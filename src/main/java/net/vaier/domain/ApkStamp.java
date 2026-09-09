package net.vaier.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Writes the <b>stamped host</b> into an Android package: the host name of the Vaier that served it, so
 * the app it becomes already knows where it came from and never asks a person to type an address.
 *
 * <p>The trick is the industry's own — Walle and VasDolly stamp release channels the same way. An APK
 * carries an <b>APK Signing Block</b> immediately before its ZIP central directory, and that block is an
 * ID-value store: v2/v3 signature verification covers the ZIP entries, the central directory and the
 * end-of-central-directory record, but <em>not</em> the block itself, and a reader ignores IDs it does
 * not recognise. So a pair can be added to a signed package and the package stays signed. Vaier holds no
 * signing key and could not re-sign one if it wanted to; this is the only way the fact can travel.
 *
 * <p>The layout, all little-endian:
 * <pre>
 *   uint64   size of block in bytes, excluding this field
 *   repeated uint64 length (id + value), uint32 id, byte[] value
 *   uint64   the same size again
 *   byte[16] "APK Sig Block 42"
 * </pre>
 *
 * <p>Two details are what make the surgery safe rather than merely plausible. The central directory
 * offset recorded in the end-of-central-directory record has to be rewritten, because the block grew and
 * the directory moved — and it is legal to rewrite because the v2 scheme deliberately excludes that one
 * field from what it signs. And the <b>verity padding</b> pair, whose only purpose is to hold the central
 * directory on a 4096-byte boundary, is shrunk or regrown so it goes on doing that job. A package with no
 * padding pair had nobody holding an alignment, so none is invented for it.
 *
 * <p>Pure bytes in, bytes out. Nothing here reads a file or knows where the package came from.
 */
public final class ApkStamp {

    /**
     * The ID the stamped host is stored under: {@code "VAIE"} in ASCII. Half of a contract with the app,
     * which reads this same ID out of its own package on first launch — so it is a published constant,
     * not an implementation detail.
     */
    public static final int STAMPED_HOST_ID = 0x56414945;

    /** Android's own padding pair. Its length is the slack that keeps the central directory aligned. */
    private static final int VERITY_PADDING_ID = 0x42726577;

    private static final String MAGIC = "APK Sig Block 42";
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final int EOCD_MIN_LENGTH = 22;
    private static final int MAX_ZIP_COMMENT = 65535;
    private static final int CD_ALIGNMENT = 4096;
    /** A pair costs 8 bytes of length and 4 of id before its value. */
    private static final int PAIR_OVERHEAD = 12;
    /** The trailing size field and the magic, which follow the pairs inside the block. */
    private static final int BLOCK_FOOTER = 24;

    private ApkStamp() {
    }

    /**
     * {@code apk} with {@code host} stamped into its signing block, or empty when there is no signing
     * block to stamp — a v1-only package, or something that is not an APK at all. Idempotent: an already
     * stamped package comes back carrying the new host and only the new host.
     */
    public static Optional<byte[]> stampedWith(byte[] apk, String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A stamped host has to be a host");
        }
        return parse(apk).map(block -> block.rewritten(apk, host));
    }

    /** The host stamped into {@code apk}, or empty when nothing stamped it. */
    public static Optional<String> hostIn(byte[] apk) {
        return parse(apk).flatMap(block -> block.pairs().stream()
            .filter(pair -> pair.id() == STAMPED_HOST_ID)
            .findFirst()
            .map(pair -> new String(pair.value(), StandardCharsets.UTF_8)));
    }

    private record Pair(int id, byte[] value) {

        int encodedLength() {
            return PAIR_OVERHEAD + value.length;
        }
    }

    /** Where the block sits and what is in it — enough to write a new one in its place. */
    private record SigningBlock(int start, int centralDirectoryOffset, int eocdOffset, List<Pair> pairs) {

        byte[] rewritten(byte[] apk, String host) {
            List<Pair> rewritten = new ArrayList<>(pairs.stream()
                .filter(pair -> pair.id() != STAMPED_HOST_ID && pair.id() != VERITY_PADDING_ID)
                .toList());
            rewritten.add(new Pair(STAMPED_HOST_ID, host.getBytes(StandardCharsets.UTF_8)));
            if (pairs.stream().anyMatch(pair -> pair.id() == VERITY_PADDING_ID)) {
                rewritten.add(new Pair(VERITY_PADDING_ID, new byte[paddingHoldingTheAlignment(rewritten)]));
            }
            return spliced(apk, rewritten);
        }

        /**
         * How long the padding pair's value has to be for the central directory to land on the same
         * 4096-byte boundary it was on. Usually a shrink — the stamp took the room the padding was
         * holding — but a stamp longer than the slack simply borrows the next page back.
         */
        private int paddingHoldingTheAlignment(List<Pair> withoutPadding) {
            int pairsBytes = withoutPadding.stream().mapToInt(Pair::encodedLength).sum();
            return Math.floorMod(
                centralDirectoryOffset - start - pairsBytes - PAIR_OVERHEAD - BLOCK_FOOTER - 8, CD_ALIGNMENT);
        }

        private byte[] spliced(byte[] apk, List<Pair> rewritten) {
            long size = rewritten.stream().mapToInt(Pair::encodedLength).sum() + (long) BLOCK_FOOTER;
            ByteBuffer block = ByteBuffer.allocate((int) (8 + size)).order(ByteOrder.LITTLE_ENDIAN);
            block.putLong(size);
            for (Pair pair : rewritten) {
                block.putLong(4L + pair.value().length);
                block.putInt(pair.id());
                block.put(pair.value());
            }
            block.putLong(size);
            block.put(MAGIC.getBytes(StandardCharsets.US_ASCII));

            int tail = apk.length - centralDirectoryOffset;
            byte[] out = new byte[start + block.capacity() + tail];
            System.arraycopy(apk, 0, out, 0, start);
            System.arraycopy(block.array(), 0, out, start, block.capacity());
            System.arraycopy(apk, centralDirectoryOffset, out, start + block.capacity(), tail);

            int movedEocd = eocdOffset - centralDirectoryOffset + start + block.capacity();
            ByteBuffer.wrap(out, movedEocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(start + block.capacity());
            return out;
        }
    }

    private static Optional<SigningBlock> parse(byte[] apk) {
        if (apk == null) {
            return Optional.empty();
        }
        int eocd = endOfCentralDirectory(apk);
        if (eocd < 0) {
            return Optional.empty();
        }
        long centralDirectory = u32(apk, eocd + 16);
        if (centralDirectory < BLOCK_FOOTER + 8 || centralDirectory > eocd) {
            return Optional.empty();
        }
        int cd = (int) centralDirectory;
        if (!MAGIC.equals(new String(apk, cd - 16, 16, StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        long size = u64(apk, cd - BLOCK_FOOTER);
        if (size < BLOCK_FOOTER || size + 8 > cd) {
            return Optional.empty();
        }
        int start = (int) (cd - size - 8);
        if (u64(apk, start) != size) {
            return Optional.empty();
        }
        return pairs(apk, start + 8, cd - BLOCK_FOOTER)
            .map(found -> new SigningBlock(start, cd, eocd, found));
    }

    private static Optional<List<Pair>> pairs(byte[] apk, int from, int to) {
        List<Pair> pairs = new ArrayList<>();
        int at = from;
        while (at < to) {
            if (at + 8 > to) {
                return Optional.empty();
            }
            long length = u64(apk, at);
            if (length < 4 || at + 8 + length > to) {
                return Optional.empty();
            }
            int id = (int) u32(apk, at + 8);
            pairs.add(new Pair(id, Arrays.copyOfRange(apk, at + 12, (int) (at + 8 + length))));
            at += (int) (8 + length);
        }
        return Optional.of(pairs);
    }

    /** Scanning back past a comment of up to 65535 bytes, which is where the record can hide. */
    private static int endOfCentralDirectory(byte[] apk) {
        int lowest = Math.max(0, apk.length - EOCD_MIN_LENGTH - MAX_ZIP_COMMENT);
        for (int at = apk.length - EOCD_MIN_LENGTH; at >= lowest; at--) {
            if (u32(apk, at) == EOCD_SIGNATURE) {
                return at;
            }
        }
        return -1;
    }

    private static long u32(byte[] apk, int at) {
        return ByteBuffer.wrap(apk, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private static long u64(byte[] apk, int at) {
        return ByteBuffer.wrap(apk, at, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
