package net.fjordomatic.application;

import net.fjordomatic.domain.DeviceCategory;
import net.fjordomatic.domain.MachineId;

public interface UpdateLanServerDeviceCategoryUseCase {

    /**
     * Sets (or, with a null/blank value, clears) a LAN server's device-category override — the
     * operator-pinned icon hint. Clearing reverts the effective category to auto-detection. A non-blank
     * value must be a valid {@link DeviceCategory} name, otherwise {@link IllegalArgumentException} is
     * thrown (surfaced as 400). Throws {@link net.fjordomatic.domain.NotFoundException} when no machine has
     * this id.
     */
    void updateDeviceCategory(MachineId machineId, String deviceCategory);
}
