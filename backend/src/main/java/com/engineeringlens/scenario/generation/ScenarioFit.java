package com.engineeringlens.scenario.generation;

import static com.engineeringlens.scenario.ScenarioCategory.AI_ML_ENGINEERING;
import static com.engineeringlens.scenario.ScenarioCategory.API_RELIABILITY;
import static com.engineeringlens.scenario.ScenarioCategory.ARCHITECTURE_REFACTORING;
import static com.engineeringlens.scenario.ScenarioCategory.CACHING;
import static com.engineeringlens.scenario.ScenarioCategory.CLOUD_ARCHITECTURE;
import static com.engineeringlens.scenario.ScenarioCategory.CONCURRENCY_CONSISTENCY;
import static com.engineeringlens.scenario.ScenarioCategory.CORRECTNESS_BUG;
import static com.engineeringlens.scenario.ScenarioCategory.DATABASE_CORRECTNESS;
import static com.engineeringlens.scenario.ScenarioCategory.DATABASE_PERFORMANCE;
import static com.engineeringlens.scenario.ScenarioCategory.DEPENDENCY_FAILURE;
import static com.engineeringlens.scenario.ScenarioCategory.ERROR_HANDLING;
import static com.engineeringlens.scenario.ScenarioCategory.FRONTEND_CLIENT;
import static com.engineeringlens.scenario.ScenarioCategory.INFRASTRUCTURE_DEPLOYMENT;
import static com.engineeringlens.scenario.ScenarioCategory.NETWORK_BEHAVIOR;
import static com.engineeringlens.scenario.ScenarioCategory.OBSERVABILITY_OPERATIONS;
import static com.engineeringlens.scenario.ScenarioCategory.PRODUCTION_BUG;
import static com.engineeringlens.scenario.ScenarioCategory.PRODUCTION_READINESS;
import static com.engineeringlens.scenario.ScenarioCategory.SCALABILITY;
import static com.engineeringlens.scenario.ScenarioCategory.SECURITY;
import static com.engineeringlens.scenario.ScenarioCategory.TESTING_GAP;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioDifficulty;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

/**
 * Which scenarios fit a lab's roles and seniority. The prompt asks for the right kind of scenario, but the model
 * doesn't always comply (a Backend Engineer lab once got React modal tasks labelled "Backend Engineer"), so the
 * plan and every built scenario are checked here, deterministically, before anything is published.
 *
 * <p>A scenario fits a role when its category is one that role works on and the code it is mainly about sits on a
 * layer that role owns (a file's layer comes from its path, not from what the model claims). Seniority sets the
 * difficulties allowed and removes categories that don't match its depth.
 */
final class ScenarioFit {

    private ScenarioFit() {
    }

    /** Which part of a system a file belongs to, judged from its path alone. */
    enum Layer {
        FRONTEND, BACKEND, INFRASTRUCTURE, ML, UNKNOWN
    }

    private static final Set<ScenarioCategory> BACKEND = EnumSet.of(PRODUCTION_BUG, CORRECTNESS_BUG, API_RELIABILITY, ERROR_HANDLING,
            DATABASE_CORRECTNESS, DATABASE_PERFORMANCE, CONCURRENCY_CONSISTENCY, CACHING, SCALABILITY, ARCHITECTURE_REFACTORING,
            SECURITY, TESTING_GAP, PRODUCTION_READINESS, DEPENDENCY_FAILURE, OBSERVABILITY_OPERATIONS);
    private static final Set<ScenarioCategory> FRONTEND = EnumSet.of(FRONTEND_CLIENT, PRODUCTION_BUG, CORRECTNESS_BUG, ERROR_HANDLING,
            API_RELIABILITY, CONCURRENCY_CONSISTENCY, CACHING, SECURITY, TESTING_GAP, ARCHITECTURE_REFACTORING, PRODUCTION_READINESS,
            DEPENDENCY_FAILURE);
    private static final Set<ScenarioCategory> OPERATIONS = EnumSet.of(INFRASTRUCTURE_DEPLOYMENT, PRODUCTION_READINESS,
            OBSERVABILITY_OPERATIONS, DEPENDENCY_FAILURE, SECURITY, SCALABILITY, CLOUD_ARCHITECTURE);
    private static final Set<ScenarioCategory> AI = EnumSet.of(AI_ML_ENGINEERING, CORRECTNESS_BUG, PRODUCTION_BUG, ERROR_HANDLING,
            API_RELIABILITY, DEPENDENCY_FAILURE, TESTING_GAP, SECURITY, CACHING, SCALABILITY, OBSERVABILITY_OPERATIONS,
            PRODUCTION_READINESS);
    private static final Set<ScenarioCategory> ML = EnumSet.of(AI_ML_ENGINEERING, CORRECTNESS_BUG, PRODUCTION_BUG, ERROR_HANDLING,
            TESTING_GAP, DATABASE_CORRECTNESS, DATABASE_PERFORMANCE, SCALABILITY);

    static final Map<ScenarioRole, Set<ScenarioCategory>> CATEGORIES = new EnumMap<>(Map.ofEntries(
            Map.entry(ScenarioRole.BACKEND_ENGINEER, BACKEND),
            Map.entry(ScenarioRole.FRONTEND_ENGINEER, FRONTEND),
            Map.entry(ScenarioRole.FULL_STACK_ENGINEER, union(BACKEND, FRONTEND)),
            Map.entry(ScenarioRole.DEVOPS_ENGINEER, union(OPERATIONS, EnumSet.of(TESTING_GAP, API_RELIABILITY))),
            Map.entry(ScenarioRole.INFRASTRUCTURE_ENGINEER, union(OPERATIONS, EnumSet.of(NETWORK_BEHAVIOR))),
            Map.entry(ScenarioRole.CLOUD_ENGINEER, union(OPERATIONS, EnumSet.of(NETWORK_BEHAVIOR, CACHING))),
            Map.entry(ScenarioRole.NETWORK_ENGINEER, EnumSet.of(NETWORK_BEHAVIOR, API_RELIABILITY, DEPENDENCY_FAILURE, SECURITY,
                    SCALABILITY, CLOUD_ARCHITECTURE, INFRASTRUCTURE_DEPLOYMENT, OBSERVABILITY_OPERATIONS, CACHING)),
            Map.entry(ScenarioRole.AI_ENGINEER, AI),
            Map.entry(ScenarioRole.ML_ENGINEER, ML),
            Map.entry(ScenarioRole.MLOPS_ENGINEER, EnumSet.of(AI_ML_ENGINEERING, INFRASTRUCTURE_DEPLOYMENT, PRODUCTION_READINESS,
                    OBSERVABILITY_OPERATIONS, SCALABILITY, DEPENDENCY_FAILURE, TESTING_GAP, CLOUD_ARCHITECTURE)),
            Map.entry(ScenarioRole.AI_ML_ENGINEER, union(AI, ML)),
            Map.entry(ScenarioRole.AI_RESEARCHER, EnumSet.of(AI_ML_ENGINEERING, CORRECTNESS_BUG, TESTING_GAP, SCALABILITY)),
            Map.entry(ScenarioRole.SOFTWARE_ARCHITECT, EnumSet.of(ARCHITECTURE_REFACTORING, SCALABILITY, CONCURRENCY_CONSISTENCY, CACHING,
                    API_RELIABILITY, DEPENDENCY_FAILURE, DATABASE_CORRECTNESS, DATABASE_PERFORMANCE, PRODUCTION_READINESS,
                    OBSERVABILITY_OPERATIONS, SECURITY, CLOUD_ARCHITECTURE)),
            Map.entry(ScenarioRole.BROAD_ENGINEERING, EnumSet.allOf(ScenarioCategory.class))));

    /** UNKNOWN (READMEs, shared config, ambiguous scripts) is allowed everywhere: it says nothing about the role. */
    static final Map<ScenarioRole, Set<Layer>> LAYERS = new EnumMap<>(Map.ofEntries(
            Map.entry(ScenarioRole.BACKEND_ENGINEER, EnumSet.of(Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.FRONTEND_ENGINEER, EnumSet.of(Layer.FRONTEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.FULL_STACK_ENGINEER, EnumSet.of(Layer.FRONTEND, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.DEVOPS_ENGINEER, EnumSet.of(Layer.INFRASTRUCTURE, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.INFRASTRUCTURE_ENGINEER, EnumSet.of(Layer.INFRASTRUCTURE, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.CLOUD_ENGINEER, EnumSet.of(Layer.INFRASTRUCTURE, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.NETWORK_ENGINEER, EnumSet.of(Layer.INFRASTRUCTURE, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.AI_ENGINEER, EnumSet.of(Layer.ML, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.ML_ENGINEER, EnumSet.of(Layer.ML, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.MLOPS_ENGINEER, EnumSet.of(Layer.ML, Layer.INFRASTRUCTURE, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.AI_ML_ENGINEER, EnumSet.of(Layer.ML, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.AI_RESEARCHER, EnumSet.of(Layer.ML, Layer.BACKEND, Layer.UNKNOWN)),
            Map.entry(ScenarioRole.SOFTWARE_ARCHITECT, EnumSet.allOf(Layer.class)),
            Map.entry(ScenarioRole.BROAD_ENGINEERING, EnumSet.allOf(Layer.class))));

    /** Roles whose work is mostly code, so their labs should be mostly executable scenarios. */
    private static final Set<ScenarioRole> CODE_ROLES = EnumSet.of(ScenarioRole.BACKEND_ENGINEER, ScenarioRole.FRONTEND_ENGINEER,
            ScenarioRole.FULL_STACK_ENGINEER, ScenarioRole.AI_ENGINEER, ScenarioRole.ML_ENGINEER, ScenarioRole.AI_ML_ENGINEER);

    static final Map<Seniority, Set<ScenarioDifficulty>> DIFFICULTIES = new EnumMap<>(Map.of(
            Seniority.BEGINNER, EnumSet.of(ScenarioDifficulty.FOUNDATIONAL),
            Seniority.SDE1, EnumSet.of(ScenarioDifficulty.FOUNDATIONAL, ScenarioDifficulty.INTERMEDIATE),
            Seniority.SDE2, EnumSet.of(ScenarioDifficulty.INTERMEDIATE, ScenarioDifficulty.ADVANCED),
            Seniority.SDE3, EnumSet.of(ScenarioDifficulty.ADVANCED, ScenarioDifficulty.EXPERT),
            Seniority.SENIOR_ARCHITECT, EnumSet.of(ScenarioDifficulty.ADVANCED, ScenarioDifficulty.EXPERT)));

    /** System-level problems are beyond a beginner; local bug fixes are below an architect. */
    static final Map<Seniority, Set<ScenarioCategory>> EXCLUDED = new EnumMap<>(Map.of(
            Seniority.BEGINNER, EnumSet.of(SCALABILITY, CLOUD_ARCHITECTURE, ARCHITECTURE_REFACTORING, CONCURRENCY_CONSISTENCY,
                    DATABASE_PERFORMANCE),
            Seniority.SDE1, EnumSet.noneOf(ScenarioCategory.class),
            Seniority.SDE2, EnumSet.noneOf(ScenarioCategory.class),
            Seniority.SDE3, EnumSet.of(CORRECTNESS_BUG),
            Seniority.SENIOR_ARCHITECT, EnumSet.of(CORRECTNESS_BUG, PRODUCTION_BUG, ERROR_HANDLING, TESTING_GAP)));

    private static final Set<String> INFRA_DIRS = Set.of("k8s", "kubernetes", "helm", "charts", "terraform", "infra", "infrastructure",
            "deploy", "deployment", "deployments", "ansible", ".circleci", "ops");
    private static final Set<String> FRONTEND_DIRS = Set.of("frontend", "client", "web", "webapp", "ui", "www", "components", "hooks",
            "styles");
    private static final Set<String> BACKEND_DIRS = Set.of("backend", "server", "api", "services", "service");
    private static final Set<String> ML_DIRS = Set.of("ml", "notebooks", "training");
    private static final Set<String> FRONTEND_EXTENSIONS = Set.of("tsx", "jsx", "vue", "svelte", "astro", "css", "scss", "sass", "less");
    private static final Set<String> BACKEND_EXTENSIONS = Set.of("py", "java", "kt", "go", "rb", "cs", "php", "rs", "scala", "ex",
            "exs", "sql");

    static Layer layer(String path) {
        String p = path.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        String[] parts = p.split("/");
        String name = parts[parts.length - 1];
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (name.startsWith("dockerfile") || name.startsWith("docker-compose") || name.startsWith("compose.")
                || name.equals("procfile") || name.equals("jenkinsfile") || name.equals(".gitlab-ci.yml") || ext.equals("tf")
                || ext.equals("tfvars") || p.startsWith(".github/workflows/") || (name.startsWith("nginx") && ext.equals("conf"))) {
            return Layer.INFRASTRUCTURE;
        }
        if (FRONTEND_EXTENSIONS.contains(ext)) {
            return Layer.FRONTEND;
        }
        if (ext.equals("ipynb")) {
            return Layer.ML;
        }
        // The outermost telling folder wins: frontend/src/api/client.ts is frontend code that calls an API.
        for (int i = 0; i < parts.length - 1; i++) {
            if (INFRA_DIRS.contains(parts[i])) {
                return Layer.INFRASTRUCTURE;
            }
            if (FRONTEND_DIRS.contains(parts[i])) {
                return Layer.FRONTEND;
            }
            if (BACKEND_DIRS.contains(parts[i])) {
                return Layer.BACKEND;
            }
            if (ML_DIRS.contains(parts[i])) {
                return Layer.ML;
            }
        }
        return BACKEND_EXTENSIONS.contains(ext) ? Layer.BACKEND : Layer.UNKNOWN;
    }

    /** The specific roles a lab's scenarios may be written for: Broad Engineering opens all of them. */
    static List<ScenarioRole> roles(ScenarioLab lab) {
        return lab.getRoles().contains(ScenarioRole.BROAD_ENGINEERING)
                ? Arrays.stream(ScenarioRole.values()).filter(r -> r != ScenarioRole.BROAD_ENGINEERING).toList()
                : lab.getRoles();
    }

    /** The categories a lab may use: those of its roles, minus what its seniority rules out. */
    static Set<ScenarioCategory> categories(ScenarioLab lab) {
        Set<ScenarioCategory> allowed = EnumSet.noneOf(ScenarioCategory.class);
        roles(lab).forEach(r -> allowed.addAll(CATEGORIES.get(r)));
        allowed.removeAll(EXCLUDED.get(lab.getSeniority()));
        return allowed;
    }

    static Set<ScenarioDifficulty> difficulties(Seniority seniority) {
        return DIFFICULTIES.get(seniority);
    }

    /**
     * Labs whose roles are all code roles, below architect level: these must be mostly CODE scenarios. Broad
     * Engineering isn't held to it, since its coverage legitimately includes deployment, cloud and network work.
     */
    static boolean codeFirst(ScenarioLab lab) {
        return lab.getSeniority() != Seniority.SENIOR_ARCHITECT && CODE_ROLES.containsAll(lab.getRoles());
    }

    /**
     * Why an outline doesn't fit the lab, or null when it does. Its main (first) file must be on a layer of its
     * primary role; secondary files are filtered separately ({@link #fitsAnyRole}).
     */
    static String misfit(ScenarioPlan.Outline o, String mainFile, ScenarioLab lab) {
        List<ScenarioRole> roles = roles(lab);
        if (!roles.contains(o.role())) {
            return "role " + o.role() + " was not selected";
        }
        if (o.applicableRoles() != null && !roles.containsAll(o.applicableRoles())) {
            return "applicableRoles may only name selected roles";
        }
        if (!CATEGORIES.get(o.role()).contains(o.category())) {
            return "category " + o.category() + " is not " + o.role() + " work";
        }
        if (EXCLUDED.get(lab.getSeniority()).contains(o.category())) {
            return "category " + o.category() + " does not fit seniority " + lab.getSeniority();
        }
        if (!DIFFICULTIES.get(lab.getSeniority()).contains(o.difficulty())) {
            return "difficulty " + o.difficulty() + " does not fit seniority " + lab.getSeniority();
        }
        Layer layer = layer(mainFile);
        if (!LAYERS.get(o.role()).contains(layer)) {
            return mainFile + " is " + layer.name().toLowerCase(Locale.ROOT) + " code, not " + o.role() + " code";
        }
        return null;
    }

    /**
     * True when some of these files are code the lab's roles own (not just READMEs or shared config). Without that,
     * a plan could only be off-role, so generation stops before asking the model.
     */
    static boolean hasCodeFor(ScenarioLab lab, Collection<String> files) {
        List<ScenarioRole> roles = roles(lab);
        return files.stream().map(ScenarioFit::layer).filter(l -> l != Layer.UNKNOWN)
                .anyMatch(l -> roles.stream().anyMatch(r -> LAYERS.get(r).contains(l)));
    }

    /** True when the file is on a layer of at least one of these roles. */
    static boolean fitsAnyRole(String file, Collection<ScenarioRole> roles) {
        Layer layer = layer(file);
        return roles.stream().anyMatch(r -> LAYERS.get(r).contains(layer));
    }

    @SafeVarargs
    private static Set<ScenarioCategory> union(Set<ScenarioCategory>... sets) {
        Set<ScenarioCategory> all = EnumSet.noneOf(ScenarioCategory.class);
        for (Set<ScenarioCategory> s : sets) {
            all.addAll(s);
        }
        return all;
    }
}
