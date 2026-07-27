package net.vaier.application;

import net.vaier.domain.MachineId;

/**
 * Delete a file or directory on a machine — the Explorer's present-only, destructive mutate (#321, slice 5).
 * A directory is deleted recursively. There is <b>no</b> time coordinate: you cannot delete the past — an
 * archive is read-only by construction — so a delete only ever touches the live filesystem. The frontend
 * gates the operation behind a typed machine-name confirmation; the backend's job is to delete safely and
 * report clearly.
 */
public interface DeleteFileUseCase {

    /**
     * Delete the file or directory at {@code path} on {@code machineId}, at the machine's own true
     * coordinates. A directory is emptied and removed recursively.
     *
     * @param path the file or directory to delete — required, absolute, and never the machine's SFTP root
     *             itself (the whole browsable tree is not deletable)
     * @throws IllegalArgumentException when {@code path} is not a browsable absolute path
     * @throws net.vaier.domain.CannotDeleteSftpRootException when {@code path} is the machine's SFTP root
     * @throws net.vaier.domain.PathOutsideSftpRootException when {@code path} is above the machine's SFTP root
     * @throws net.vaier.domain.NotFoundException when no machine has that id, or the path is not there
     * @throws net.vaier.domain.PermissionDeniedException when the SSH user may not delete the path
     */
    void delete(MachineId machineId, String path);
}
