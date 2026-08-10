package net.fjordomatic.adapter.driven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DnsJavaPublicResolverAdapterTest {

    DnsJavaPublicResolverAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DnsJavaPublicResolverAdapter();
    }

    // --- resolveAddresses: the public-resolver view, i.e. what Let's Encrypt sees (#331) ---

    @Test
    void resolveAddresses_returnsWhatTheFirstAnsweringPublicResolverSays() {
        adapter.addressQueryAtResolver = (fqdn, resolverHost) -> List.of("52.29.74.114");

        assertThat(adapter.resolveAddresses("9f3c1a.b21d70.example.com"))
            .containsExactly("52.29.74.114");
    }

    @Test
    void resolveAddresses_fallsBackToTheNextPublicResolverWhenTheFirstHasNoAnswer() {
        adapter.addressQueryAtResolver = (fqdn, resolverHost) ->
            "1.1.1.1".equals(resolverHost) ? List.of() : List.of("52.29.74.114");

        assertThat(adapter.resolveAddresses("9f3c1a.b21d70.example.com"))
            .containsExactly("52.29.74.114");
    }

    @Test
    void resolveAddresses_isEmptyWhenNoPublicResolverAnswers() {
        adapter.addressQueryAtResolver = (fqdn, resolverHost) -> List.of();

        assertThat(adapter.resolveAddresses("9f3c1a.b21d70.example.com")).isEmpty();
    }

    @Test
    void resolveAddresses_asksEveryPublicResolverBeforeGivingUp() {
        List<String> asked = new ArrayList<>();
        adapter.addressQueryAtResolver = (fqdn, resolverHost) -> {
            asked.add(resolverHost);
            return List.of();
        };

        adapter.resolveAddresses("9f3c1a.b21d70.example.com");

        assertThat(asked).containsExactly("1.1.1.1", "8.8.8.8");
    }
}
