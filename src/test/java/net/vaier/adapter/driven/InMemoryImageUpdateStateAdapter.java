package net.vaier.adapter.driven;

import net.vaier.domain.RegistryDigestHistory;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.port.ForPersistingImageUpdateState;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Test-only in-memory stand-in for {@link ForPersistingImageUpdateState}. Real deployments use
 * {@link ImageUpdateStateFileAdapter}; tests that only care about the <em>decisions</em>
 * {@link net.vaier.domain.ImageUpdateTracker} makes from the remembered state use this so they never touch
 * a temp directory. A restart is not expressible with it — a test about surviving one must use the file
 * adapter, because surviving is exactly what this fake cannot do.
 */
public class InMemoryImageUpdateStateAdapter implements ForPersistingImageUpdateState {

    private Set<ScopedImage> outOfDate = new LinkedHashSet<>();
    private RegistryDigestHistory history = RegistryDigestHistory.empty();

    @Override
    public synchronized Set<ScopedImage> loadOutOfDate() {
        return Set.copyOf(outOfDate);
    }

    @Override
    public synchronized void saveOutOfDate(Set<ScopedImage> images) {
        outOfDate = new LinkedHashSet<>(images);
    }

    @Override
    public synchronized RegistryDigestHistory loadRegistryDigestHistory() {
        return history;
    }

    @Override
    public synchronized void saveRegistryDigestHistory(RegistryDigestHistory history) {
        this.history = history;
    }
}
