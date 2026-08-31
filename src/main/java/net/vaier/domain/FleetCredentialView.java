package net.vaier.domain;

/**
 * The redacted, safe-to-leave-the-process shape of a {@link FleetCredential}: where it goes, at what
 * mode, merely <em>whether</em> a secret is held, and whether the operator has ever distributed it.
 * Never the content, and never the digest — a digest of a short secret is a secret.
 *
 * <p>This is the only shape a GET may return to the browser, mirroring {@link HostCredentialView}.
 */
public record FleetCredentialView(String name, String targetPath, String mode, boolean hasSecret,
                                  boolean distributed) {
}
