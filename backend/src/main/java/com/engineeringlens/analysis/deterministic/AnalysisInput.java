package com.engineeringlens.analysis.deterministic;

import java.util.List;

import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.profile.RepositoryProfile;

/**
 * Everything a rule may look at: the inventory, the profile, and the few file contents fetched in
 * this run. Deliberately nothing about the developer: the same repository yields the same signals
 * whoever submitted it.
 */
public record AnalysisInput(List<InventoryFile> inventory, RepositoryProfile profile, SourceTexts texts) {

    public List<InventoryFile> relevant() {
        return inventory.stream().filter(f -> !f.ignored()).toList();
    }
}
