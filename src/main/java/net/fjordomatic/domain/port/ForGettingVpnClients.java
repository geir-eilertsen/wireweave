package net.fjordomatic.domain.port;

import net.fjordomatic.domain.VpnClient;
import java.util.List;

public interface ForGettingVpnClients {
    List<VpnClient> getClients();
}
