package net.vaier.application;

import net.vaier.domain.AccessSource;

import java.util.List;

/** Every place allowed accesses have come from that Vaier still remembers, for the map and the security view. */
public interface GetAccessSourcesUseCase {

    List<AccessSource> getAccessSources();
}
