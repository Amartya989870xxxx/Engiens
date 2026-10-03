package com.engineeringlens.analysis.deterministic;

import static com.engineeringlens.analysis.deterministic.Signal.evidence;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;
import com.engineeringlens.analysis.common.ReviewDimension;

/** What persistence technology and structure exists. Never whether the schema or queries are good. */
public final class PersistenceRules {

    private PersistenceRules() {
    }

    public static final AnalysisRule DATABASE = AnalysisRule.of("PERSISTENCE.DATABASE", ReviewDimension.PERSISTENCE,
            "Databases detected from drivers, connection settings or Compose services.",
            (rule, in) -> in.profile().databases().stream()
                    .map(d -> Signal.of(rule, Severity.INFO, d.confidence(), "Database detected: " + d.name() + ".",
                            evidence("database", d.name(), "evidence", d.evidence())))
                    .toList());

    public static final AnalysisRule TOOLING = AnalysisRule.of("PERSISTENCE.TOOLING", ReviewDimension.PERSISTENCE,
            "ORMs and migration tools detected from dependencies or well-known files.",
            (rule, in) -> in.profile().persistence().stream()
                    .map(d -> Signal.of(rule, Severity.INFO, d.confidence(), "Persistence tooling detected: " + d.name() + ".",
                            evidence("tool", d.name(), "evidence", d.evidence())))
                    .toList());

    public static final AnalysisRule MIGRATIONS = AnalysisRule.of("PERSISTENCE.MIGRATIONS", ReviewDimension.PERSISTENCE,
            "Database migration files, or their absence when a database is in use.",
            (rule, in) -> {
                List<String> migrations = in.relevant().stream().map(InventoryFile::path).filter(RepoPaths::isMigration).toList();
                if (!migrations.isEmpty()) {
                    Set<String> dirs = new TreeSet<>();
                    migrations.forEach(p -> dirs.add(RepoPaths.parent(p)));
                    return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH,
                            migrations.size() + " migration " + (migrations.size() == 1 ? "file" : "files") + " detected.",
                            evidence("migrationFileCount", migrations.size(), "directories", dirs.stream().limit(5).toList())));
                }
                if (in.profile().databases().isEmpty()) {
                    return List.of();
                }
                return List.of(Signal.of(rule, Severity.INFO, Confidence.MEDIUM,
                        "A database is in use, but no recognised migration files were detected.",
                        evidence("migrationFileCount", 0, "databases",
                                in.profile().databases().stream().map(d -> d.name()).toList())));
            });

    private static final Set<String> DATA_ACCESS_FOLDERS = Set.of("repositories", "repository", "dao", "daos", "persistence");

    public static final AnalysisRule DATA_ACCESS_LAYER = AnalysisRule.of("PERSISTENCE.DATA_ACCESS_LAYER", ReviewDimension.PERSISTENCE,
            "Repository/DAO-style files or folders.",
            (rule, in) -> {
                List<String> files = in.relevant().stream()
                        .filter(f -> RepoPaths.isCode(f.language()) && !RepoPaths.isTestFile(f.path(), f.language()))
                        .map(InventoryFile::path)
                        .filter(p -> RepoPaths.folders(p).stream().anyMatch(DATA_ACCESS_FOLDERS::contains)
                                || RepoPaths.lowerName(p).matches(".+(repository|dao)\\.(java|kt|ts|js|py|go|cs)|crud\\.py|repository\\.py"))
                        .toList();
                return files.isEmpty() ? List.of()
                        : List.of(Signal.of(rule, Severity.INFO, Confidence.MEDIUM,
                                files.size() + " repository/DAO-style " + (files.size() == 1 ? "file" : "files") + " detected.",
                                evidence("fileCount", files.size(), "examples", files.stream().limit(5).toList())));
            });

    static final List<AnalysisRule> ALL = List.of(DATABASE, TOOLING, MIGRATIONS, DATA_ACCESS_LAYER);
}
