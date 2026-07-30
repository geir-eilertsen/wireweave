package net.vaier.application.service;

import net.vaier.domain.MachineType;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityServiceTest {

    @Mock ForGettingPeerConfigurations peerConfigProvider;
    @Mock ForWritingCrowdSecWhitelist forWritingCrowdSecWhitelist;

    private SecurityService service() {
        SecurityService service = new SecurityService(peerConfigProvider, forWritingCrowdSecWhitelist);
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "dockerBridgeCidr", "172.20.0.0/16");
        return service;
    }

    private static PeerConfiguration relay(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    @Test
    void refreshTrustedNetworks_assemblesFromConfigAndPeersThenWrites() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "192.168.3.0/24"),
            relay("colina27", "192.168.1.0/24")));

        service().refreshTrustedNetworks();

        ArgumentCaptor<TrustedNetworks> captor = ArgumentCaptor.forClass(TrustedNetworks.class);
        verify(forWritingCrowdSecWhitelist).write(captor.capture());
        assertThat(captor.getValue().allCidrs()).containsExactly(
            "10.13.13.0/24", "172.20.0.0/16", "192.168.3.0/24", "192.168.1.0/24");
    }
}
