package net.vaier.application;

import net.vaier.domain.DeviceCategory;
import net.vaier.domain.MachineId;

public interface UpdateLanServerDeviceCategoryUseCase {

    /**
     * Sets (or, with a null/blank value, clears) a LAN server's device-category override — the
     * operator-pinned icon hint. Clearing reverts the effective category to auto-detection. A non-blank
     * value must be a valid {@link DeviceCategory} name, otherwise {@link IllegalArgumentException} is
     * thrown (surfaced as 400). Throws {@link net.vaier.domain.NotFoundException} when no machine has
     * this id.
     */
    void updateDeviceCategory(MachineId machineId, String deviceCategory);
}
