package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.ViewableFile;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Open a file on a machine <b>in the browser</b> — the Explorer's "display it, don't save it" destination.
 * The sibling of {@link DownloadFileUseCase}, and deliberately not a mode of it: a download is always an
 * attachment of unknown bytes, and turning that into "opens in a tab for some files" would change a verb the
 * operator already relies on. Two verbs, two endpoints, two answers.
 *
 * <p>Only a <b>viewable</b> file can be opened, and {@link ViewableFile} — the domain — is the sole judge of
 * what that means and of the media type the bytes are handed over as. Asking to open anything else is a bad
 * request, never a silent fallback to serving it inline under some other type.
 *
 * <p>Reading is all this does, so {@code at} may name an archive: opening a file as it was in the past works
 * exactly as downloading it does.
 */
public interface ViewFileUseCase {

    /**
     * Prepare the file at {@code path} on {@code machineId} at time {@code at} to be displayed in the browser.
     *
     * @param path the file to open, at the machine's own true coordinates — required and concrete
     * @param at an archive id to open the file as it was then, or {@code null} for the live filesystem
     * @throws IllegalArgumentException when {@code path} is not absolute, or the file is not viewable
     * @throws net.vaier.domain.NotFoundException when the machine, or the path, is not there
     */
    View openForView(MachineId machineId, String path, String at);

    /**
     * A file ready to be displayed: its {@code filename} (for {@code Content-Disposition: inline}), its
     * {@code sizeBytes}, the {@code mediaType} the browser is handed it as, the
     * {@code contentSecurityPolicy} it must be served under — both the domain's choice, so no adapter has to
     * re-derive either — and a {@code writer} that streams the bytes to a given {@link OutputStream} when
     * called, opening the SFTP read only then.
     */
    record View(String filename, long sizeBytes, String mediaType, String contentSecurityPolicy,
                Consumer<OutputStream> writer) {
    }
}
