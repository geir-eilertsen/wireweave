package net.vaier.config;

import java.util.Optional;
import net.vaier.domain.WildcardDnsReport;
import org.springframework.stereotype.Component;

/**
 * The verdict from the boot-time wildcard-DNS check, held so the Settings pane can state it long after
 * the log line that carried it has scrolled away (#331).
 *
 * <p>Empty until the lifecycle has run — which is honest: "not checked yet" is a different thing from
 * "checked and found nothing", and only one of them is worth warning an operator about.
 */
@Component
public class WildcardDnsStatusHolder {

    private volatile WildcardDnsReport report;

    /** The last verdict recorded, or empty before the lifecycle has run. */
    public Optional<WildcardDnsReport> report() {
        return Optional.ofNullable(report);
    }

    /** Records what the check found, replacing any earlier verdict. */
    public void record(WildcardDnsReport report) {
        this.report = report;
    }
}
