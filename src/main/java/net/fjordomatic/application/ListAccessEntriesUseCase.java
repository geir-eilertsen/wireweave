package net.fjordomatic.application;

import net.fjordomatic.domain.AccessEntry;

import java.util.List;

public interface ListAccessEntriesUseCase {
    List<AccessEntry> listAccessEntries();
}
