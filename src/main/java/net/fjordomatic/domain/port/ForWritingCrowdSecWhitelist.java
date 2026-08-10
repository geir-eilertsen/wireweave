package net.fjordomatic.domain.port;

import net.fjordomatic.domain.TrustedNetworks;

/**
 * Renders the operator's {@link TrustedNetworks} allowlist to wherever CrowdSec reads it from
 * (#329 Slice 1). The driven side owns the file format and location — the domain only decides
 * which CIDRs belong on the list.
 */
public interface ForWritingCrowdSecWhitelist {

    void write(TrustedNetworks trustedNetworks);
}
