package net.fjordomatic.application;

import net.fjordomatic.domain.AccessSource;

import java.util.List;

/** Every place allowed accesses have come from that Fjord still remembers, for the map and the security view. */
public interface GetAccessSourcesUseCase {

    List<AccessSource> getAccessSources();
}
