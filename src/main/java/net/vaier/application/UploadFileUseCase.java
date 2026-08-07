package net.vaier.application;

import net.vaier.domain.Upload;

import java.io.InputStream;

/**
 * Put one file from the operator's browser into a directory on a machine — the Explorer's mirror of a
 * {@link DownloadFileUseCase download}. Present-only, like every write: a machine's past is a read-only
 * mounted archive, so there is no time coordinate and nowhere in it to put a file.
 *
 * <p>Deliberately not a {@link StartTransferUseCase transfer}. A transfer moves a coordinate between two
 * machines and reports its progress from the server; an upload has no source machine and no point in time,
 * and its progress is the browser's own to report as it sends the bytes.
 */
public interface UploadFileUseCase {

    /**
     * Stream {@code content} into the {@link Upload}'s destination. The bytes are piped straight through to
     * the machine and never held whole, so a large file costs no more memory than a small one.
     *
     * @param upload  where the file is going and whether an existing file of that name may be replaced
     * @param content the file's bytes — the caller owns the stream and closes it
     * @throws net.vaier.domain.ConflictException when the name is already taken and no replacement was asked
     *                                            for, or when a folder wears the name (which no replacement
     *                                            settles)
     * @throws net.vaier.domain.PathOutsideSftpRootException when the destination is above the machine's SFTP root
     * @throws net.vaier.domain.NotFoundException when no machine has that id, or the directory is not there
     * @throws net.vaier.domain.PermissionDeniedException when the SSH user may not write into the directory
     */
    void upload(Upload upload, InputStream content);
}
