package com.engineeringlens.analysis.deterministic;

import static com.engineeringlens.analysis.deterministic.Signal.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;
import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.profile.RepositoryProfile;

/** Build, configuration and delivery signals. Presence is reported, never "production-ready". */
public final class ProductionReadinessRules {

    private ProductionReadinessRules() {
    }

    public static final AnalysisRule README = AnalysisRule.of("PRODUCTION_READINESS.README", ReviewDimension.PRODUCTION_READINESS,
            "Whether a README exists at the repository root.",
            (rule, in) -> {
                RepositoryProfile.Documentation doc = in.profile().documentation();
                return List.of(doc.readme()
                        ? Signal.of(rule, Severity.INFO, Confidence.HIGH, "README found at " + doc.readmePath() + ".",
                                evidence("path", doc.readmePath())).at(doc.readmePath(), null)
                        : Signal.of(rule, Severity.LOW, Confidence.HIGH, "No README was found at the repository root.",
                                evidence("readme", false)));
            });

    private static final Pattern ENV_READ = Pattern.compile(
            "os\\.environ|os\\.getenv\\(|process\\.env[.\\[]|import\\.meta\\.env\\.|System\\.getenv\\(|\\$\\{[A-Z][A-Z0-9_]*(:[^}]*)?}"
                    + "|os\\.Getenv\\(|env::var\\(|config\\([\"'][A-Z][A-Z0-9_]+[\"']");

    /** Fetched files that read configuration from environment variables. */
    static List<String> filesReadingEnv(AnalysisInput in) {
        return in.texts().all().entrySet().stream()
                .filter(e -> !RepoPaths.isEnvTemplate(e.getKey()) && ENV_READ.matcher(e.getValue()).find())
                .map(Map.Entry::getKey).toList();
    }

    public static final AnalysisRule ENV_VARIABLES = AnalysisRule.of("PRODUCTION_READINESS.ENV_VARIABLES",
            ReviewDimension.PRODUCTION_READINESS, "Whether inspected files read configuration from environment variables.",
            (rule, in) -> {
                List<String> files = filesReadingEnv(in);
                return files.isEmpty() ? List.of()
                        : List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH,
                                "Configuration is read from environment variables in " + files.size()
                                        + (files.size() == 1 ? " inspected file." : " inspected files."),
                                evidence("files", files.stream().limit(5).toList())));
            });

    public static final AnalysisRule ENV_EXAMPLE = AnalysisRule.of("PRODUCTION_READINESS.ENV_EXAMPLE", ReviewDimension.PRODUCTION_READINESS,
            "Whether an environment template (.env.example or similar) documents required settings.",
            (rule, in) -> {
                RepositoryProfile.Configuration c = in.profile().configuration();
                if (c.envExample()) {
                    return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH, "Environment template found: "
                            + String.join(", ", c.envExampleFiles()) + ".", evidence("files", c.envExampleFiles())));
                }
                List<String> readers = filesReadingEnv(in);
                return readers.isEmpty() ? List.of()
                        : List.of(Signal.of(rule, Severity.LOW, Confidence.MEDIUM,
                                "Environment variables are read, but no .env.example (or similar) template was found.",
                                evidence("filesReadingEnvironment", readers.stream().limit(5).toList())));
            });

    public static final AnalysisRule CONTAINERS = AnalysisRule.of("PRODUCTION_READINESS.CONTAINERS", ReviewDimension.PRODUCTION_READINESS,
            "Whether Dockerfiles or Compose files exist. Says nothing about whether the app is deployed.",
            (rule, in) -> {
                RepositoryProfile.Deployment d = in.profile().deployment();
                if (!d.dockerfile() && !d.compose()) {
                    return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH, "No Dockerfile or Compose file was detected.",
                            evidence("dockerfiles", List.of(), "composeFiles", List.of())));
                }
                List<String> parts = new ArrayList<>();
                if (d.dockerfile()) {
                    parts.add("Dockerfile");
                }
                if (d.compose()) {
                    parts.add("Compose file");
                }
                return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH, "Container setup detected: " + String.join(", ", parts) + ".",
                        evidence("dockerfiles", d.dockerfiles(), "composeFiles", d.composeFiles())));
            });

    public static final AnalysisRule CI = AnalysisRule.of("PRODUCTION_READINESS.CI", ReviewDimension.PRODUCTION_READINESS,
            "Whether a continuous-integration workflow is configured.",
            (rule, in) -> {
                RepositoryProfile.Deployment d = in.profile().deployment();
                if (!d.ci()) {
                    return List.of(Signal.of(rule, Severity.LOW, Confidence.HIGH, "No CI workflow was detected.",
                            evidence("ciProviders", List.of())));
                }
                List<String> providers = d.ciProviders().stream().map(RepositoryProfile.Detection::name).toList();
                return List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH, "CI configuration detected: " + String.join(", ", providers) + ".",
                        evidence("ciProviders", providers, "files",
                                d.ciProviders().stream().flatMap(p -> p.evidence().stream()).toList())));
            });

    public static final AnalysisRule DEPLOYMENT_CONFIG = AnalysisRule.of("PRODUCTION_READINESS.DEPLOYMENT_CONFIG",
            ReviewDimension.PRODUCTION_READINESS, "Deployment or infrastructure configuration files that exist.",
            (rule, in) -> {
                List<String> files = in.profile().deployment().deploymentConfigs();
                return files.isEmpty() ? List.of()
                        : List.of(Signal.of(rule, Severity.INFO, Confidence.HIGH,
                                "Deployment configuration detected: " + String.join(", ", files) + ".", evidence("files", files)));
            });

    private static final Pattern LOCALHOST = Pattern.compile("\\blocalhost\\b|\\b127\\.0\\.0\\.1\\b");

    public static final AnalysisRule LOCALHOST_IN_CONFIG = AnalysisRule.of("PRODUCTION_READINESS.LOCALHOST_IN_CONFIG",
            ReviewDimension.PRODUCTION_READINESS,
            "Application config files (not env templates or Compose files) that refer to localhost.",
            (rule, in) -> {
                List<Signal> signals = new ArrayList<>();
                in.texts().all().forEach((path, text) -> {
                    if (!RepoPaths.isConfigFile(path) || RepoPaths.isEnvTemplate(path) || RepoPaths.isComposeFile(path)
                            || RepoPaths.isTestFile(path, null) || path.contains("/test/")) {
                        return;
                    }
                    List<Integer> lines = new ArrayList<>();
                    String[] all = text.split("\\R", -1);
                    for (int i = 0; i < all.length; i++) {
                        if (LOCALHOST.matcher(all[i]).find()) {
                            lines.add(i + 1);
                        }
                    }
                    if (!lines.isEmpty()) {
                        List<Integer> shown = lines.stream().limit(5).toList();
                        signals.add(Signal.of(rule, Severity.INFO, Confidence.HIGH,
                                "Configuration in " + path + " refers to localhost (line" + (shown.size() == 1 ? " " : "s ")
                                        + shown.toString().replaceAll("[\\[\\]]", "") + ").",
                                evidence("lines", shown, "occurrences", lines.size())).at(path, shown.get(0)));
                    }
                });
                return signals;
            });

    private static final java.util.Set<String> NPM_LOCKFILES = java.util.Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "bun.lockb", "bun.lock", "npm-shrinkwrap.json");

    public static final AnalysisRule LOCKFILE = AnalysisRule.of("PRODUCTION_READINESS.LOCKFILE", ReviewDimension.PRODUCTION_READINESS,
            "package.json files with no lockfile beside them or in any parent folder (workspaces share a root lockfile).",
            (rule, in) -> {
                List<String> lockDirs = in.relevant().stream().map(InventoryFile::path)
                        .filter(p -> NPM_LOCKFILES.contains(RepoPaths.lowerName(p))).map(RepoPaths::parent).toList();
                // A package.json that declares no dependencies (a workspace root, say) has nothing to lock.
                java.util.Set<String> empty = new java.util.HashSet<>();
                in.profile().manifests().stream().filter(m -> Integer.valueOf(0).equals(m.declaredDependencies()))
                        .forEach(m -> empty.add(m.path()));
                return in.relevant().stream().map(InventoryFile::path)
                        .filter(p -> RepoPaths.lowerName(p).equals("package.json") && !empty.contains(p))
                        .filter(p -> lockDirs.stream().noneMatch(dir -> covers(dir, RepoPaths.parent(p))))
                        .map(p -> Signal.of(rule, Severity.LOW, Confidence.HIGH,
                                "No lockfile found for " + p + ", so installs can resolve different dependency versions over time.",
                                evidence("manifest", p)).at(p, null))
                        .toList();
            });

    private static boolean covers(String lockDir, String packageDir) {
        return lockDir.isEmpty() || packageDir.equals(lockDir) || packageDir.startsWith(lockDir + "/");
    }

    public static final AnalysisRule DEPENDENCY_MANIFESTS = AnalysisRule.of("PRODUCTION_READINESS.DEPENDENCY_MANIFESTS",
            ReviewDimension.PRODUCTION_READINESS, "Declared dependency counts per manifest. No vulnerability scanning.",
            (rule, in) -> in.profile().manifests().stream()
                    .map(m -> Signal.of(rule, Severity.INFO, Confidence.HIGH,
                            m.declaredDependencies() == null
                                    ? m.path() + " was read; its dependencies are not counted for this format."
                                    : m.path() + " declares " + m.declaredDependencies() + " dependencies.",
                            evidence("manifest", m.path(), "ecosystem", m.ecosystem(), "declaredDependencies", m.declaredDependencies()))
                            .at(m.path(), null))
                    .toList());

    static final List<AnalysisRule> ALL = List.of(README, ENV_EXAMPLE, ENV_VARIABLES, CONTAINERS, CI, DEPLOYMENT_CONFIG,
            LOCALHOST_IN_CONFIG, LOCKFILE, DEPENDENCY_MANIFESTS);
}
