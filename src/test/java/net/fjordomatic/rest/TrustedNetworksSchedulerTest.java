package net.fjordomatic.rest;

import net.fjordomatic.application.RefreshTrustedNetworksUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TrustedNetworksSchedulerTest {

    RefreshTrustedNetworksUseCase refreshTrustedNetworksUseCase;
    TrustedNetworksScheduler scheduler;

    @BeforeEach
    void setUp() {
        refreshTrustedNetworksUseCase = mock(RefreshTrustedNetworksUseCase.class);
        scheduler = new TrustedNetworksScheduler(refreshTrustedNetworksUseCase);
    }

    @Test
    void refresh_callsTheUseCase() {
        scheduler.refresh();

        verify(refreshTrustedNetworksUseCase).refreshTrustedNetworks();
    }

    @Test
    void refresh_doesNotPropagateAFailure() {
        doThrow(new RuntimeException("boom")).when(refreshTrustedNetworksUseCase).refreshTrustedNetworks();

        assertThatCode(() -> scheduler.refresh()).doesNotThrowAnyException();
    }
}
