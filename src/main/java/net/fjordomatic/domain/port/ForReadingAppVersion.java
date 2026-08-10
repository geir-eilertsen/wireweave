package net.fjordomatic.domain.port;

/**
 * Reads the running Fjord build's version — the value baked in at package time — so the operator
 * can always see which version is deployed. A driven port because the version comes from build
 * metadata (infrastructure), not from the domain.
 */
public interface ForReadingAppVersion {

    /** The deployed Fjord version, or a stable placeholder when no build metadata is present. */
    String currentVersion();
}
