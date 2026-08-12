package net.vaier.application;

import net.vaier.domain.UnidentifiedDeviceException;

public interface ForgetMyPositionUseCase {

    /**
     * Forgets the calling device's reported position <em>and</em> revokes its device claim — one action,
     * because a claim outliving the position it recorded is a tracking channel nobody asked for.
     *
     * <p>The device is identified exactly as in {@link ReportMyPositionUseCase}: tunnel first, then claim.
     *
     * @throws UnidentifiedDeviceException when neither identifies a machine.
     */
    void forgetMyPosition(String callerIp, String claimToken);
}
