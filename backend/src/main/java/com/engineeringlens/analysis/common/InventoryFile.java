package com.engineeringlens.analysis.common;

/** One file from an imported repository's inventory, decoupled from the JPA entity so analysis is easy to test. */
public record InventoryFile(String path, String language, long sizeBytes, boolean ignored, String ignoreReason) {

    public String fileName() {
        return RepoPaths.fileName(path);
    }
}
