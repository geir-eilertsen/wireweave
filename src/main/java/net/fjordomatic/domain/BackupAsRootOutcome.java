package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForReadyingBackupClients.ReadyingOutcome;

/**
 * What came of an operator saying yes to <b>Back up as root</b> for one machine — the outcome of
 * {@link BackupJob#enablingBackupAsRoot}.
 *
 * <p>The accept is one action but it stands on two facts, and they can disagree: the machine has to grant its
 * SSH user passwordless root for borg, and the job has to ask for it. Enabling the flag on a machine that has
 * not granted it does not produce a better backup — it produces <em>no</em> backup, because every run then
 * dies on {@code sudo -n} before borg starts. So this record keeps the two apart rather than collapsing them
 * into "it worked":
 *
 * @param job      the job as it now stands — flag flipped only when the grant is actually in place
 * @param granted  whether the machine can run borg as root right now (probed, never assumed)
 * @param changed  whether {@code job} differs from the stored one, i.e. whether it needs saving
 * @param readying what Fjord did about a missing grant — the detached install it launched, or the script it
 *                 staged for the operator — and {@code null} when nothing needed installing
 */
public record BackupAsRootOutcome(BackupJob job, boolean granted, boolean changed, ReadyingOutcome readying) {

    /** The machine grants root borg and the job now asks for it: tonight's run reads everything. */
    static BackupAsRootOutcome enabled(BackupJob job) {
        return new BackupAsRootOutcome(job, true, true, null);
    }

    /** The machine grants root borg and the job already asked for it — nothing to do, nothing to save. */
    static BackupAsRootOutcome alreadyOn(BackupJob job) {
        return new BackupAsRootOutcome(job, true, false, null);
    }

    /**
     * The machine does not grant root borg, so the grant was requested and the job is left exactly as it was.
     * The install is detached, which is precisely why the flag does not move here: Fjord cannot yet say the
     * grant landed, and the operator must not be told it did.
     */
    static BackupAsRootOutcome grantMissing(BackupJob job, ReadyingOutcome readying) {
        return new BackupAsRootOutcome(job, false, false, readying);
    }
}
