package com.engineeringlens.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;

/** Builds file inventories for tests, the way the import phase would have recorded them. */
public final class Fixtures {

    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("py", "Python"), Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("js", "JavaScript"),
            Map.entry("jsx", "JavaScript"), Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("go", "Go"),
            Map.entry("rs", "Rust"), Map.entry("sql", "SQL"), Map.entry("json", "JSON"), Map.entry("yml", "YAML"),
            Map.entry("yaml", "YAML"), Map.entry("md", "Markdown"), Map.entry("xml", "XML"), Map.entry("toml", "TOML"),
            Map.entry("properties", "Properties"), Map.entry("css", "CSS"), Map.entry("html", "HTML"));

    private Fixtures() {
    }

    /** Relevant files of 1 KB each. */
    public static List<InventoryFile> inventory(String... paths) {
        List<InventoryFile> files = new ArrayList<>();
        for (String path : paths) {
            files.add(file(path, 1_000));
        }
        return files;
    }

    public static InventoryFile file(String path, long size) {
        String name = RepoPaths.lowerName(path);
        String language = name.equals("dockerfile") ? "Docker" : null;
        int dot = name.lastIndexOf('.');
        if (language == null && dot > 0) {
            language = LANGUAGES.get(name.substring(dot + 1));
        }
        return new InventoryFile(path, language, size, false, null);
    }

    public static InventoryFile ignored(String path, String reason) {
        return new InventoryFile(path, null, 1_000, true, reason);
    }

    public static List<InventoryFile> with(List<InventoryFile> base, InventoryFile... more) {
        List<InventoryFile> all = new ArrayList<>(base);
        all.addAll(List.of(more));
        return all;
    }
}
