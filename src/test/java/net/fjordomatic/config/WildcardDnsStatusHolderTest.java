package net.fjordomatic.config;

import java.util.List;
import net.fjordomatic.domain.WildcardDnsReport;
import net.fjordomatic.domain.WildcardDnsStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WildcardDnsStatusHolderTest {

    @Test
    void holdsNothingBeforeTheLifecycleHasRun() {
        assertThat(new WildcardDnsStatusHolder().report()).isEmpty();
    }

    @Test
    void holdsTheLastReportTheLifecycleRecorded() {
        WildcardDnsStatusHolder holder = new WildcardDnsStatusHolder();
        WildcardDnsReport report = new WildcardDnsReport(WildcardDnsStatus.COVERED,
            "9f3c1a.example.com", "52.29.74.114", List.of("52.29.74.114"));

        holder.record(report);

        assertThat(holder.report()).contains(report);
    }

    @Test
    void aLaterRunReplacesTheEarlierReport() {
        WildcardDnsStatusHolder holder = new WildcardDnsStatusHolder();
        holder.record(new WildcardDnsReport(WildcardDnsStatus.NOT_RESOLVING,
            "9f3c1a.example.com", "52.29.74.114", List.of()));
        WildcardDnsReport later = new WildcardDnsReport(WildcardDnsStatus.COVERED,
            "b21d70.example.com", "52.29.74.114", List.of("52.29.74.114"));

        holder.record(later);

        assertThat(holder.report()).contains(later);
    }
}
