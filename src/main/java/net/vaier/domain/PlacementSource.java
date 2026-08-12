package net.vaier.domain;

/** Which evidence a {@link Placement} rests on. */
public enum PlacementSource {

    /** The device's own browser measured it (W3C Geolocation). Metres-accurate, and about the device. */
    REPORTED,

    /** The geolocation of the peer's endpoint IP — a carrier's registry point, not a device's whereabouts. */
    ISP_ESTIMATE
}
