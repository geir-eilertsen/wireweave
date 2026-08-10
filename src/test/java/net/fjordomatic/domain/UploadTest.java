package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForBrowsingRemoteFiles;
import net.fjordomatic.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Upload value object: the browser's file, the directory it is going into, and whether an existing file
 * of that name may be replaced. Every decision an upload makes is here — what the destination path is, and
 * whether the name is already taken — so neither the service, the controller nor the browser holds a copy.
 */
@ExtendWith(MockitoExtension.class)
class UploadTest {

    @Mock ForBrowsingRemoteFiles files;

    private static final MachineId APALVEIEN5 = TestMachineIds.of("apalveien5");

    private static SshTarget target() {
        return SshTarget.on("10.13.13.6",
            new HostCredential(APALVEIEN5, "geir", AuthMethod.PASSWORD, "pw", null, false), "SHA256:pinned");
    }

    private static InputStream content() {
        return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
    }

    /** The destination name is free — SFTP finds nothing there. */
    private void nothingIsThere(String jailPath) {
        when(files.stat(any(), eq(jailPath))).thenThrow(new NotFoundException("No such directory: " + jailPath));
    }

    private void fileIsThere(String jailPath) {
        when(files.stat(any(), eq(jailPath))).thenReturn(new RemoteStat(false, 120));
    }

    private void folderIsThere(String jailPath) {
        when(files.stat(any(), eq(jailPath))).thenReturn(new RemoteStat(true, 4096));
    }

    // --- what the destination path is ------------------------------------------------------------------

    @Test
    void destinationPath_joinsTheFilenameOntoTheDirectory() {
        Upload upload = Upload.into(APALVEIEN5, "/home/geir", "notes.txt", false);

        assertThat(upload.destinationPath()).isEqualTo("/home/geir/notes.txt");
    }

    @Test
    void destinationPath_intoTheRoot_doesNotDoubleTheSeparator() {
        assertThat(Upload.into(APALVEIEN5, "/", "notes.txt", false).destinationPath())
            .isEqualTo("/notes.txt");
    }

    @Test
    void into_normalisesTheDirectoryTheBrowserSent() {
        assertThat(Upload.into(APALVEIEN5, "/home//geir/./docs/", "notes.txt", false).destinationPath())
            .isEqualTo("/home/geir/docs/notes.txt");
    }

    @Test
    void into_refusesADirectoryThatIsNotAbsolute() {
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "home/geir", "notes.txt", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute");
    }

    @Test
    void into_refusesADirectoryThatClimbsAboveTheRoot() {
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/../..", "notes.txt", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("climb above the root");
    }

    /**
     * The filename arrives from a multipart part, which a hostile client writes by hand — so it is refused
     * unless it is one path segment. A name carrying separators is how an upload would otherwise escape the
     * directory the operator is standing in.
     */
    @Test
    void into_refusesAFilenameThatIsNotASinglePathSegment() {
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "../../etc/passwd", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "docs/notes.txt", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "..", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "note\0s.txt", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "  ", false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- writing, and the one refusal that guards it ---------------------------------------------------

    @Test
    void writeTo_whenTheNameIsFree_streamsTheContentToTheDestination() {
        nothingIsThere("/home/geir/notes.txt");
        InputStream content = content();

        Upload.into(APALVEIEN5, "/home/geir", "notes.txt", false)
            .writeTo(target(), SftpRoot.NONE, files, content);

        ArgumentCaptor<SshTarget> where = ArgumentCaptor.forClass(SshTarget.class);
        verify(files).upload(where.capture(), eq("/home/geir/notes.txt"), eq(content));
        assertThat(where.getValue().host()).isEqualTo("10.13.13.6");
    }

    @Test
    void writeTo_ontoAnExistingFile_isRefusedAsAConflict_andNothingIsWritten() {
        fileIsThere("/home/geir/notes.txt");

        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "notes.txt", false)
            .writeTo(target(), SftpRoot.NONE, files, content()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("notes.txt")
            .hasMessageContaining("/home/geir");

        verify(files, never()).upload(any(), any(), any());
    }

    @Test
    void writeTo_ontoAnExistingFile_withOverwriteAsked_writesOverIt() {
        fileIsThere("/home/geir/notes.txt");

        Upload.into(APALVEIEN5, "/home/geir", "notes.txt", true)
            .writeTo(target(), SftpRoot.NONE, files, content());

        verify(files).upload(any(), eq("/home/geir/notes.txt"), any());
    }

    /**
     * A folder wearing the name is not something an overwrite can settle: replacing a directory with a file
     * would mean deleting the directory and everything in it, which no upload ever asked for.
     */
    @Test
    void writeTo_ontoAnExistingFolder_isRefused_evenWhenOverwriteIsAsked() {
        folderIsThere("/home/geir/docs");

        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/home/geir", "docs", true)
            .writeTo(target(), SftpRoot.NONE, files, content()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("folder");

        verify(files, never()).upload(any(), any(), any());
    }

    // --- the jail: SFTP is asked for the path it can actually see ---------------------------------------

    @Test
    void writeTo_onAJailedMachine_asksSftpForTheJailPath() {
        nothingIsThere("/homes/geir/notes.txt");

        Upload.into(APALVEIEN5, "/volume1/homes/geir", "notes.txt", false)
            .writeTo(target(), new SftpRoot("/volume1"), files, content());

        // The destination stays the machine's own true coordinate; only what SFTP is asked for is jailed.
        verify(files).upload(any(), eq("/homes/geir/notes.txt"), any());
    }

    @Test
    void writeTo_aboveTheMachinesSftpRoot_isRefusedBeforeAnythingIsAsked() {
        assertThatThrownBy(() -> Upload.into(APALVEIEN5, "/etc", "notes.txt", false)
            .writeTo(target(), new SftpRoot("/volume1"), files, content()))
            .isInstanceOf(PathOutsideSftpRootException.class);

        verify(files, never()).upload(any(), any(), any());
    }
}
