package net.fjordomatic.domain.port;

import net.fjordomatic.domain.GeoLocation;

import java.util.Optional;

public interface ForGeolocatingIps {
    Optional<GeoLocation> locate(String ipAddress);
}
