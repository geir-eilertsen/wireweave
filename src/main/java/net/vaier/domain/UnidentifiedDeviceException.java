package net.vaier.domain;

/**
 * Vaier cannot tell which device is talking: the request came from no peer's tunnel IP and carries no
 * valid {@link DeviceClaim}.
 *
 * <p>A refusal, not a fault — and deliberately not a 404: the caller is authenticated, it simply has not
 * established which of the operator's devices it is.
 */
public class UnidentifiedDeviceException extends RuntimeException {

    public UnidentifiedDeviceException(String message) {
        super(message);
    }

    /** The only reason this is ever thrown, phrased once, beside the type rather than in a caller. */
    public static UnidentifiedDeviceException becauseNothingIdentifiesTheDevice() {
        return new UnidentifiedDeviceException(
            "Vaier cannot tell which device this is: the request came from no peer's tunnel IP "
                + "and carries no device claim.");
    }
}
