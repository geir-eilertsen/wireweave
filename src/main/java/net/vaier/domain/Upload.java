package net.vaier.domain;

import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * One file on its way from the operator's browser into a directory on a machine — the mirror of a
 * {@link net.vaier.domain.port.ForBrowsingRemoteFiles#download download}, and the whole of it: what the
 * destination path is, and whether the name may be taken.
 *
 * <p><b>Not a {@link Transfer}.</b> A Transfer carries a coordinate from one machine to another — it has a
 * source machine, a point in time, and a no-op-onto-its-own-coordinate rule, and all three are invariants it
 * documents carefully. An upload has none of them: its source is a browser, which is not a machine and has no
 * past. Forcing it into that record would mean nulling out half of it and weakening what the other half
 * promises, so an upload is its own thin path.
 *
 * <p><b>Present-only, like every other write.</b> There is no time coordinate: a machine's past is a read-only
 * mounted archive, so there is nowhere in it to put a file.
 *
 * <p>The decisions live here rather than on the service, the controller or the browser. The
 * destination is a {@linkplain FileEntry#childPath child path} of the directory, built from the same domain
 * rule a listed entry's path is built from — so a filename that is not a single path segment cannot escape the
 * folder the operator is standing in, however the multipart part was written by hand. And whether the name is
 * free is asked of the machine, here, before a byte is sent ({@link #writeTo}).
 */
public record Upload(MachineId machineId, String directory, String filename, boolean overwrite) {

    public Upload {
        Objects.requireNonNull(machineId, "An upload must name the machine it is going to");
        directory = FileEntry.normalisePath(directory);
        FileEntry.requireValidName(filename);
    }

    /**
     * An upload of {@code filename} into the directory at {@code directory} on {@code machineId}, replacing a
     * file already wearing that name only when {@code overwrite} says so.
     */
    public static Upload into(MachineId machineId, String directory, String filename, boolean overwrite) {
        return new Upload(machineId, directory, filename, overwrite);
    }

    /** The machine's own true coordinate the bytes land on — what the operator will see in the listing. */
    public String destinationPath() {
        return FileEntry.childPath(directory, filename);
    }

    /**
     * Stream {@code content} onto the machine, refusing rather than silently replacing what is already there.
     *
     * <p>Three answers, in one round trip. <b>Nothing there</b> — write it. <b>A file there</b> — a
     * {@link ConflictException}, unless this upload was made with {@code overwrite}, which is the operator
     * having been asked and having said yes. <b>A folder there</b> — a conflict that no overwrite settles,
     * because replacing a directory with a file means deleting the directory and everything inside it, and no
     * upload ever asked for that.
     *
     * <p>The domain calls the port; the service only passes it in. What SFTP is actually asked for is the
     * {@code root}-mapped path — a destination above the machine's SFTP root is
     * {@linkplain PathOutsideSftpRootException refused} by that mapping, before anything is asked of the
     * machine — while {@link #destinationPath} stays the machine's own coordinate.
     */
    public void writeTo(SshTarget target, SftpRoot root, ForBrowsingRemoteFiles files, InputStream content) {
        String sftpPath = root.toJailPath(destinationPath());
        alreadyThere(target, sftpPath, files).ifPresent(existing -> {
            if (existing.directory()) {
                throw new ConflictException("There is already a folder called \"" + filename + "\" in "
                    + directory + ". A folder cannot be replaced by a file.");
            }
            if (!overwrite) {
                throw new ConflictException("\"" + filename + "\" is already in " + directory + ".");
            }
        });
        files.upload(target, sftpPath, content);
    }

    /**
     * What the machine has at the destination, or empty when the name is free. Absent is the ordinary answer
     * here — an upload into a folder is usually a new name — so a {@link NotFoundException} is this question's
     * "no", not a failure. Every other refusal (an unreadable parent, a machine that cannot be reached) stays
     * a failure and travels on, because "Vaier could not look" must never be reported as "the name is free".
     */
    private static Optional<RemoteStat> alreadyThere(SshTarget target, String sftpPath,
                                                     ForBrowsingRemoteFiles files) {
        try {
            return Optional.of(files.stat(target, sftpPath));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
