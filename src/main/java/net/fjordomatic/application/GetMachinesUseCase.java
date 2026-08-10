package net.fjordomatic.application;

import net.fjordomatic.domain.Machine;

import java.util.List;

public interface GetMachinesUseCase {

    List<Machine> getAllMachines();
}
