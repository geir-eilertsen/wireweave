package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-shot retrieval marker for peer configs (#202). The marker is a sibling file of the peer's
 * .conf, inside the peer directory at {@code <wireguardConfigPath>/<peerId>/<peerId>.conf.viewed}.
 * Placing it there means the existing WireGuard delete flow (which removes the whole peer dir)
 * also clears the marker for free, so a delete + recreate of the same name produces a peer whose
 * config is once-again retrievable.
 *
 * Pre-existing peers (created before this change) have no marker file and are therefore
 * "not yet viewed" — the first GET after upgrade is allowed, then the peer is locked.
 */
@Component
@Slf4j
public class FilePeerConfigRetrievalTracker implements ForTrackingPeerConfigRetrieval {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Override
    public boolean markViewedIfNotAlready(String peerId) {
        Path peerDir = Paths.get(wireguardConfigPath, peerId);
        Path marker = peerDir.resolve(peerId + ".conf.viewed");

        try {
            // Atomic check-and-set: createFile throws FileAlreadyExistsException if the marker
            // exists. This races safely against concurrent first-views — exactly one wins.
            Files.createFile(marker);
            log.info("Marked peer config as viewed: {}", peerId);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (NoSuchFileException e) {
            // Parent dir doesn't exist — the peer isn't known. Distinguishable from "already
            // viewed" so the caller can return 404 instead of 410.
            throw new IllegalStateException("Peer directory not found: " + peerId, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write retrieval marker for " + peerId, e);
        }
    }

    @Override
    public boolean isAlreadyViewed(String peerId) {
        return Files.exists(Paths.get(wireguardConfigPath, peerId, peerId + ".conf.viewed"));
    }

    @Override
    public void resetViewed(String peerId) {
        Path marker = Paths.get(wireguardConfigPath, peerId, peerId + ".conf.viewed");
        try {
            if (Files.deleteIfExists(marker)) {
                log.info("Reset peer config viewed marker: {}", peerId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reset retrieval marker for " + peerId, e);
        }
    }
}
