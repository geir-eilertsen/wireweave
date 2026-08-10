package net.vaier.domain;

/**
 * Vaier could not read a backup repository's archives.
 *
 * <p>Exists because the alternative was an empty list, and "this repository holds no archives" is a very
 * different fact from "I could not read this repository". The second is the only sign an operator gets that
 * a machine's backups have become unreachable — a wrong passphrase, a moved repository, a host that will
 * not answer — and returned as emptiness it reads as the first, on the very screen someone opens when they
 * want a file back.
 *
 * <p>Carries borg's own words. A repository is opened over SSH by a program that says precisely what went
 * wrong ("Repository access aborted", "Failed to create/acquire the lock"), and no status code Vaier could
 * invent would be as useful as passing that sentence through.
 */
public class ArchivesUnreadableException extends RuntimeException {

    public ArchivesUnreadableException(String repositoryName, String reason) {
        super("Could not read the archives in " + repositoryName
            + (reason == null || reason.isBlank() ? "" : ": " + reason));
    }
}
