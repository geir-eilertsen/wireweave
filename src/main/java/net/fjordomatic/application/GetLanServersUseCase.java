package net.fjordomatic.application;

import net.fjordomatic.domain.port.ForGettingLanServers.LanServerView;

import java.util.List;

public interface GetLanServersUseCase {

    List<LanServerView> getAll();
}
