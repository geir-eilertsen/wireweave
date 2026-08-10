package net.fjordomatic.application;

import net.fjordomatic.domain.VpnClient;
import java.util.List;

public interface GetVpnClientsUseCase {
    List<VpnClient> getClients();
}
