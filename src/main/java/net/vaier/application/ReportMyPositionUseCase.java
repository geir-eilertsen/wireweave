package net.vaier.application;

import net.vaier.domain.UnidentifiedDeviceException;

public interface ReportMyPositionUseCase {

    /**
     * Records where the calling device says it is.
     *
     * <p>Which device that is comes from the {@code callerIp} resolving to a peer's tunnel IP, or failing
     * that from the device claim {@code claimToken} names — never from anything the caller asserts about
     * itself, which is why no machine id is a parameter here.
     *
     * @throws UnidentifiedDeviceException when neither identifies a machine.
     * @throws IllegalArgumentException    when the coordinates are not a point on Earth.
     */
    void reportMyPosition(String callerIp, String claimToken,
                          Double latitude, Double longitude, Double accuracyMetres);
}
