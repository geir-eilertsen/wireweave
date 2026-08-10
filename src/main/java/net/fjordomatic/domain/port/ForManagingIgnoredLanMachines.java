package net.fjordomatic.domain.port;

import java.util.Set;

/**
 * The operator's ignore-list for discovered LAN machines (issue #246) — the parallel of
 * {@link ForManagingIgnoredServices} for hosts a scan surfaces. Keyed on
 * {@link net.fjordomatic.domain.DiscoveredLanMachine#ignoreKey()}.
 */
public interface ForManagingIgnoredLanMachines {
    Set<String> getIgnoredKeys();
    void ignore(String ignoreKey);
    void unignore(String ignoreKey);
}
