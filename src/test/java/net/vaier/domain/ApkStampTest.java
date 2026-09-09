package net.vaier.domain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.vaier.testsupport.SyntheticApk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stamping the served package with the host name of the Vaier that served it, so the app never has to
 * ask a person to type an address. The mechanism is the industry's own channel-stamping trick: the APK
 * Signing Block is an ID-value store that sits outside everything the v2/v3 signature covers, and
 * unknown IDs in it are ignored — so a pair can be added and the package stays signed.
 *
 * <p>That is only true while the surgery is exact, which is why these tests are byte-level. The block's
 * two size fields must keep agreeing, the magic must stay put, the central directory offset in the
 * end-of-central-directory record must be moved by exactly what the block grew by, and the verity
 * padding — whose whole job is holding the central directory on its 4096-byte alignment — must be
 * shrunk or regrown to keep it there. Get any of it wrong and the result is not a broken stamp, it is
 * an APK no phone will install.
 */
class ApkStampTest {

    private static final String HOST = "vaier.eilertsen.family";
    private static final int V2_SIGNATURE_ID = 0x7109871a;
    private static final int VERITY_PADDING_ID = 0x42726577;
    private static final int CD_ALIGNMENT = 4096;

    @TempDir
    Path dir;

    @Test
    void theServedPackageCarriesTheHostThatServedIt() {
        byte[] apk = SyntheticApk.signed();

        byte[] stamped = ApkStamp.stampedWith(apk, HOST).orElseThrow();

        assertThat(ApkStamp.hostIn(stamped)).contains(HOST);
    }

    @Test
    void anUnstampedPackageNamesNoHost() {
        assertThat(ApkStamp.hostIn(SyntheticApk.signed())).isEmpty();
    }

    @Test
    void theCentralDirectoryMovesByExactlyWhatTheBlockGrewBy() {
        // The offset in the end-of-central-directory record is the one field the v2 scheme deliberately
        // leaves out of what it signs, and it is the one field that has to change. Left stale, every
        // reader — Android's installer included — walks off into the middle of the signing block.
        byte[] apk = SyntheticApk.signed();
        int before = centralDirectoryOffset(apk);

        byte[] stamped = ApkStamp.stampedWith(apk, HOST).orElseThrow();
        int after = centralDirectoryOffset(stamped);

        assertThat(after - before).isEqualTo(stamped.length - apk.length);
        assertThat(after).as("and it points at a real central directory")
            .satisfies(o -> assertThat(readU32(stamped, o)).isEqualTo(0x02014b50L));
    }

    @Test
    void theVerityPaddingKeepsTheCentralDirectoryOnItsAlignment() {
        // The padding pair exists for exactly one reason: to hold the central directory on a 4096-byte
        // boundary. Adding a pair without shrinking it back would slide the whole directory off.
        byte[] apk = SyntheticApk.signed();
        assertThat(centralDirectoryOffset(apk) % CD_ALIGNMENT).as("the fixture models an aligned APK").isZero();

        byte[] stamped = ApkStamp.stampedWith(apk, HOST).orElseThrow();

        assertThat(centralDirectoryOffset(stamped) % CD_ALIGNMENT).isZero();
        assertThat(pairIds(stamped)).endsWith(VERITY_PADDING_ID);
    }

    @Test
    void aPackageWithNoPaddingIsLeftOnWhateverAlignmentItHad() {
        // No padding pair means nobody was holding an alignment, so there is none to preserve. The block
        // grows by exactly the pair: 8 bytes of length, 4 of id, and the host itself.
        byte[] apk = SyntheticApk.signedWithoutVerityPadding();

        byte[] stamped = ApkStamp.stampedWith(apk, HOST).orElseThrow();

        assertThat(stamped.length - apk.length).isEqualTo(12 + HOST.getBytes(StandardCharsets.UTF_8).length);
        assertThat(pairIds(stamped)).containsExactly(V2_SIGNATURE_ID, ApkStamp.STAMPED_HOST_ID);
    }

    @Test
    void theBlockStillDescribesItself() {
        // Two size fields and a magic. They are how every reader finds the block at all, so they have to
        // keep agreeing with the block's actual length after the surgery.
        byte[] stamped = ApkStamp.stampedWith(SyntheticApk.signed(), HOST).orElseThrow();
        int cd = centralDirectoryOffset(stamped);

        assertThat(new String(stamped, cd - 16, 16, StandardCharsets.US_ASCII)).isEqualTo("APK Sig Block 42");
        long trailingSize = readU64(stamped, cd - 24);
        long leadingSize = readU64(stamped, (int) (cd - trailingSize - 8));
        assertThat(leadingSize).isEqualTo(trailingSize);
        assertThat(cd - trailingSize - 8).as("the block starts where the sizes say it does").isNotNegative();
    }

    @Test
    void stampingTwiceReplacesTheHostRatherThanAddingASecond() {
        // A redeploy under a different domain must not leave the app two answers to choose between.
        byte[] once = ApkStamp.stampedWith(SyntheticApk.signed(), HOST).orElseThrow();

        byte[] twice = ApkStamp.stampedWith(once, "vaier.example.com").orElseThrow();

        assertThat(ApkStamp.hostIn(twice)).contains("vaier.example.com");
        assertThat(pairIds(twice).stream().filter(id -> id == ApkStamp.STAMPED_HOST_ID).count()).isEqualTo(1);
        assertThat(centralDirectoryOffset(twice) % CD_ALIGNMENT).isZero();
    }

    @Test
    void theStampedPackageIsStillAReadableZip() throws IOException {
        // The cheapest proof that the central directory offset was rewritten correctly: a ZIP reader
        // starts from that offset, so a stale one fails to open the file at all.
        byte[] stamped = ApkStamp.stampedWith(SyntheticApk.signed(), HOST).orElseThrow();
        Path file = dir.resolve("vaier.apk");
        Files.write(file, stamped);

        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(SyntheticApk.ENTRY_NAME);
            assertThat(entry).isNotNull();
            assertThat(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(SyntheticApk.ENTRY_CONTENT);
        }
    }

    @Test
    void nothingElseInThePackageIsTouched() {
        byte[] apk = SyntheticApk.signed();
        int blockStart = signingBlockStart(apk);

        byte[] stamped = ApkStamp.stampedWith(apk, HOST).orElseThrow();

        assertThat(Arrays.copyOf(stamped, blockStart))
            .as("every entry before the block").isEqualTo(Arrays.copyOf(apk, blockStart));
        assertThat(signingBlockStart(stamped)).as("and the block still starts there").isEqualTo(blockStart);
    }

    @Test
    void aPackageWithNoSigningBlockCannotBeStamped() {
        // A v1-only APK. Never a failure that stops the download — the caller serves it unstamped.
        assertThat(ApkStamp.stampedWith(SyntheticApk.v1Only(), HOST)).isEmpty();
        assertThat(ApkStamp.hostIn(SyntheticApk.v1Only())).isEmpty();
    }

    @Test
    void somethingThatIsNotAnArchiveAtAllCannotBeStamped() {
        assertThat(ApkStamp.stampedWith(new byte[]{1, 2, 3}, HOST)).isEmpty();
        assertThat(ApkStamp.hostIn(new byte[0])).isEmpty();
    }

    @Test
    void thereIsNoHostToStampWithoutAHost() {
        assertThatThrownBy(() -> ApkStamp.stampedWith(SyntheticApk.signed(), "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIdIsTheOneTheAppLooksFor() {
        // "VAIE" in ASCII. The app-side reader is pointed at this constant, so it is part of the
        // contract between the two halves and not an implementation detail to be tidied.
        assertThat(ApkStamp.STAMPED_HOST_ID).isEqualTo(0x56414945);
    }

    // --- an independent reader, so the production parser is never both author and judge ---

    private static List<Integer> pairIds(byte[] apk) {
        int cd = centralDirectoryOffset(apk);
        long size = readU64(apk, cd - 24);
        int at = (int) (cd - size - 8) + 8;
        int end = cd - 24;
        List<Integer> ids = new ArrayList<>();
        while (at < end) {
            long len = readU64(apk, at);
            ids.add((int) readU32(apk, at + 8));
            at += 8 + len;
        }
        return ids;
    }

    private static int signingBlockStart(byte[] apk) {
        int cd = centralDirectoryOffset(apk);
        return (int) (cd - readU64(apk, cd - 24) - 8);
    }

    private static int centralDirectoryOffset(byte[] apk) {
        return (int) readU32(apk, endOfCentralDirectory(apk) + 16);
    }

    private static int endOfCentralDirectory(byte[] apk) {
        for (int i = apk.length - 22; i >= 0; i--) {
            if (readU32(apk, i) == 0x06054b50L) {
                return i;
            }
        }
        throw new IllegalStateException("not a zip");
    }

    private static long readU32(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private static long readU64(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

}
