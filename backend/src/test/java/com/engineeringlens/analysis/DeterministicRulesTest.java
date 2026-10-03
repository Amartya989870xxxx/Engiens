package com.engineeringlens.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.deterministic.AnalysisInput;
import com.engineeringlens.analysis.deterministic.AnalysisRule;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.deterministic.DeterministicAnalyzer;
import com.engineeringlens.analysis.deterministic.PersistenceRules;
import com.engineeringlens.analysis.deterministic.ProductionReadinessRules;
import com.engineeringlens.analysis.deterministic.SecretScanner;
import com.engineeringlens.analysis.deterministic.SecurityRules;
import com.engineeringlens.analysis.deterministic.Severity;
import com.engineeringlens.analysis.deterministic.Signal;
import com.engineeringlens.analysis.deterministic.StructureRules;
import com.engineeringlens.analysis.deterministic.TestingRules;
import com.engineeringlens.analysis.profile.RepositoryProfiler;

/** Each rule: a positive case, a negative case, and the false positives it must not produce. */
class DeterministicRulesTest {

    private static final RepositoryProfiler PROFILER = new RepositoryProfiler();
    private static final RepositoryProfiler.ProjectFacts FACTS =
            new RepositoryProfiler.ProjectFacts("demo", "asha", "main", "PUBLIC", "c0ffee", true);

    static AnalysisInput input(List<InventoryFile> inventory, Map<String, String> texts) {
        SourceTexts t = SourceTexts.of(texts);
        return new AnalysisInput(inventory, PROFILER.profile(FACTS, inventory, t), t);
    }

    static AnalysisInput input(String... paths) {
        return input(Fixtures.inventory(paths), Map.of());
    }

    static List<Signal> run(AnalysisRule rule, AnalysisInput in) {
        return rule.evaluate(in);
    }

    static Signal only(AnalysisRule rule, AnalysisInput in) {
        List<Signal> s = rule.evaluate(in);
        assertThat(s).as(rule.id()).hasSize(1);
        return s.get(0);
    }

    // ---- Testing -----------------------------------------------------
    @Test
    void countsRecognisedTestFiles() {
        Signal s = only(TestingRules.TEST_FILES, input("src/app.py", "tests/test_app.py", "src/App.test.tsx", "README.md"));
        assertThat(s.severity()).isEqualTo(Severity.INFO);
        assertThat(s.message()).isEqualTo("2 recognised test files (50.0% of relevant files).");
        assertThat(s.evidence()).containsEntry("testFileCount", 2);
    }

    @Test
    void saysNotRecognisedRatherThanNoTests() {
        Signal s = only(TestingRules.TEST_FILES, input("src/app.py"));
        assertThat(s.severity()).isEqualTo(Severity.LOW);
        assertThat(s.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(s.message()).isEqualTo("No recognised automated test files were detected.");
    }

    @Test
    void fixturesInATestFolderAreNotTestFiles() {
        Signal s = only(TestingRules.TEST_FILES, input("src/app.py", "tests/fixtures/orders.json"));
        assertThat(s.evidence()).containsEntry("testFileCount", 0);
    }

    @Test
    void reportsDeclaredTestFrameworks() {
        AnalysisInput in = input(Fixtures.inventory("package.json", "package-lock.json"),
                Map.of("package.json", "{\"devDependencies\":{\"vitest\":\"^5\"}}"));
        assertThat(only(TestingRules.TEST_FRAMEWORKS, in).message()).isEqualTo("Test framework detected: Vitest.");
        assertThat(run(TestingRules.TEST_FRAMEWORKS, input("src/app.py"))).isEmpty();
    }

    // ---- ProductionReadiness -----------------------------------------
    @Test
    void readmeAtTheRootOnly() {
        assertThat(only(ProductionReadinessRules.README, input("README.md")).severity()).isEqualTo(Severity.INFO);
        Signal missing = only(ProductionReadinessRules.README, input("docs/README.md", "app.py"));
        assertThat(missing.severity()).isEqualTo(Severity.LOW);
        assertThat(missing.message()).isEqualTo("No README was found at the repository root.");
    }

    @Test
    void envTemplateMissingIsOnlyRaisedWhenEnvironmentVariablesAreRead() {
        AnalysisInput readsEnv = input(Fixtures.inventory("src/main/resources/application.properties"),
                Map.of("src/main/resources/application.properties", "spring.datasource.url=${DB_URL}\n"));
        assertThat(only(ProductionReadinessRules.ENV_EXAMPLE, readsEnv).severity()).isEqualTo(Severity.LOW);
        assertThat(only(ProductionReadinessRules.ENV_VARIABLES, readsEnv).evidence().get("files"))
                .isEqualTo(List.of("src/main/resources/application.properties"));

        assertThat(run(ProductionReadinessRules.ENV_EXAMPLE, input("app.py"))).isEmpty();
        assertThat(only(ProductionReadinessRules.ENV_EXAMPLE, input(".env.example")).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void placeholdersInsideTheEnvTemplateDoNotCountAsReadingEnv() {
        AnalysisInput in = input(Fixtures.inventory(".env.example"), Map.of(".env.example", "API_URL=${HOST}/api"));
        assertThat(run(ProductionReadinessRules.ENV_VARIABLES, in)).isEmpty();
    }

    @Test
    void containersAndCiArePresenceObservations() {
        AnalysisInput with = input("Dockerfile", "docker-compose.yml", ".github/workflows/ci.yml");
        assertThat(only(ProductionReadinessRules.CONTAINERS, with).message()).isEqualTo("Container setup detected: Dockerfile, Compose file.");
        assertThat(only(ProductionReadinessRules.CI, with).message()).isEqualTo("CI configuration detected: GitHub Actions.");

        AnalysisInput without = input("app.py");
        assertThat(only(ProductionReadinessRules.CONTAINERS, without).severity()).isEqualTo(Severity.INFO);
        Signal noCi = only(ProductionReadinessRules.CI, without);
        assertThat(noCi.severity()).isEqualTo(Severity.LOW);
        assertThat(noCi.message()).isEqualTo("No CI workflow was detected.");
    }

    @Test
    void deploymentConfigIsListedWhenPresent() {
        assertThat(only(ProductionReadinessRules.DEPLOYMENT_CONFIG, input("vercel.json", "k8s/deployment.yaml"))
                .evidence().get("files")).isEqualTo(List.of("vercel.json", "k8s/deployment.yaml"));
        assertThat(run(ProductionReadinessRules.DEPLOYMENT_CONFIG, input("app.py"))).isEmpty();
    }

    @Test
    void localhostInApplicationConfigButNotInTemplatesOrCompose() {
        AnalysisInput in = input(Fixtures.inventory("config/application.yml", ".env.example", "docker-compose.yml"), Map.of(
                "config/application.yml", "server:\n  url: http://localhost:8080\n",
                ".env.example", "API=http://localhost:8080",
                "docker-compose.yml", "ports:\n  - 127.0.0.1:5432:5432"));
        Signal s = only(ProductionReadinessRules.LOCALHOST_IN_CONFIG, in);
        assertThat(s.file()).isEqualTo("config/application.yml");
        assertThat(s.line()).isEqualTo(2);
        assertThat(s.message()).isEqualTo("Configuration in config/application.yml refers to localhost (line 2).");
    }

    @Test
    void lockfilesAreSharedByWorkspacePackages() {
        Signal missing = only(ProductionReadinessRules.LOCKFILE, input("package.json", "index.js"));
        assertThat(missing.severity()).isEqualTo(Severity.LOW);

        assertThat(run(ProductionReadinessRules.LOCKFILE, input("package.json", "pnpm-lock.yaml", "packages/ui/package.json"))).isEmpty();
        assertThat(run(ProductionReadinessRules.LOCKFILE, input("frontend/package.json", "frontend/package-lock.json"))).isEmpty();
        assertThat(run(ProductionReadinessRules.LOCKFILE, input("frontend/package.json", "backend/package-lock.json"))).hasSize(1);
    }

    @Test
    void dependencyCountsPerManifest() {
        AnalysisInput in = input(Fixtures.inventory("requirements.txt"), Map.of("requirements.txt", "fastapi\nuvicorn\n"));
        assertThat(only(ProductionReadinessRules.DEPENDENCY_MANIFESTS, in).message()).isEqualTo("requirements.txt declares 2 dependencies.");
    }

    // ---- Security ----------------------------------------------------
    @Test
    void committedSecretFilesAreFlaggedButTemplatesAreNot() {
        List<Signal> s = run(SecurityRules.SECRET_FILE, input(Fixtures.with(Fixtures.inventory(".env", ".env.example",
                "certs/server.pem", "config/.env.production", ".env.sample"),
                Fixtures.ignored("node_modules/pkg/test.pem", "Dependency directory")), Map.of()));
        assertThat(s).extracting(Signal::file).containsExactly(".env", "certs/server.pem", "config/.env.production");
        assertThat(s).allSatisfy(x -> assertThat(x.severity()).isEqualTo(Severity.MEDIUM));
    }

    @Test
    void wellKnownCredentialFormatsAreFlaggedWithoutStoringTheValue() {
        String token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
        AnalysisInput in = input(Fixtures.inventory("config/settings.py"),
                Map.of("config/settings.py", "DEBUG = True\nGITHUB = '" + token + "'\n"));
        Signal s = only(SecurityRules.SECRET_PATTERN, in);
        assertThat(s.severity()).isEqualTo(Severity.HIGH);
        assertThat(s.line()).isEqualTo(2);
        assertThat(s.message()).doesNotContain(token);
        assertThat(s.evidence().toString()).doesNotContain(token);
    }

    @Test
    void genericWordsAndDocumentationExamplesAreNotAccusations() {
        AnalysisInput in = input(Fixtures.inventory("config/settings.py"), Map.of("config/settings.py",
                "PASSWORD = 'secret'\nAWS_KEY = 'AKIAIOSFODNN7EXAMPLE'\napi_key = os.getenv('API_KEY')\n"));
        assertThat(run(SecurityRules.SECRET_PATTERN, in)).isEmpty();
    }

    @Test
    void gitignoreAtTheRoot() {
        assertThat(only(SecurityRules.GITIGNORE, input("app.py")).severity()).isEqualTo(Severity.LOW);
        assertThat(run(SecurityRules.GITIGNORE, input(".gitignore", "app.py"))).isEmpty();
    }

    @Test
    void scannerReportsKindAndLineOnly() {
        assertThat(SecretScanner.scan("a\n-----BEGIN RSA PRIVATE KEY-----\nb"))
                .containsExactly(new SecretScanner.Match("private key", 2));
        assertThat(SecretScanner.scan("key = AIzaXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")).isEmpty();
    }

    // ---- Structure ---------------------------------------------------
    @Test
    void largeSourceFilesOnlyCodeLargestFirstCappedAtFive() {
        List<InventoryFile> files = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            files.add(Fixtures.file("src/big" + i + ".py", 50_000 + i * 1_000));
        }
        files.add(Fixtures.file("data/seed.json", 900_000));
        files.add(Fixtures.file("src/small.py", 2_000));
        List<Signal> s = run(StructureRules.LARGE_SOURCE_FILE, input(files, Map.of()));
        assertThat(s).extracting(Signal::file).containsExactly("src/big7.py", "src/big6.py", "src/big5.py", "src/big4.py", "src/big3.py");
        assertThat(s.get(0).message()).isEqualTo("src/big7.py is 56 KB, large for a single source file.");
    }

    @Test
    void layerLikeDirectories() {
        Signal s = only(StructureRules.LAYER_DIRECTORIES, input("app/controllers/orders.py", "app/services/orders.py",
                "app/repositories/orders.py"));
        assertThat(s.message()).isEqualTo("Layer-like directories detected: route/controller, service, repository/DAO.");
        assertThat(run(StructureRules.LAYER_DIRECTORIES, input("main.py", "utils.py"))).isEmpty();
    }

    @Test
    void frontendBackendSplitNeedsBothAtTheTop() {
        assertThat(run(StructureRules.FRONTEND_BACKEND_SPLIT, input("frontend/src/App.tsx", "backend/app/main.py"))).hasSize(1);
        assertThat(run(StructureRules.FRONTEND_BACKEND_SPLIT, input("frontend/src/App.tsx", "src/server/app.ts"))).isEmpty();
    }

    @Test
    void entrypoints() {
        assertThat(only(StructureRules.ENTRYPOINTS, input("app/main.py", "app/util.py")).evidence().get("entrypoints"))
                .isEqualTo(List.of("app/main.py"));
        assertThat(run(StructureRules.ENTRYPOINTS, input("lib/util.py"))).isEmpty();
    }

    @Test
    void centralErrorHandlerByCodeMarkerOrName() {
        AnalysisInput byCode = input(Fixtures.inventory("src/main/java/x/ApiErrors.java"),
                Map.of("src/main/java/x/ApiErrors.java", "@RestControllerAdvice\nclass ApiErrors {}"));
        assertThat(only(StructureRules.CENTRAL_ERROR_HANDLER, byCode).confidence()).isEqualTo(Confidence.HIGH);

        Signal byName = only(StructureRules.CENTRAL_ERROR_HANDLER, input("src/main/java/x/GlobalExceptionHandler.java"));
        assertThat(byName.confidence()).isEqualTo(Confidence.MEDIUM);

        // An error page or an ErrorBoundary component isn't centralised error handling.
        assertThat(run(StructureRules.CENTRAL_ERROR_HANDLER, input("public/error.html", "src/ErrorBoundary.tsx"))).isEmpty();
    }

    // ---- Persistence -------------------------------------------------
    @Test
    void migrationsOrTheirAbsenceNextToADatabase() {
        assertThat(only(PersistenceRules.MIGRATIONS, input("db/migrations/001_init.sql", "db/migrations/002_orders.sql")).message())
                .isEqualTo("2 migration files detected.");

        AnalysisInput dbNoMigrations = input(Fixtures.inventory("requirements.txt"), Map.of("requirements.txt", "psycopg2\n"));
        assertThat(only(PersistenceRules.MIGRATIONS, dbNoMigrations).confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(only(PersistenceRules.DATABASE, dbNoMigrations).message()).isEqualTo("Database detected: PostgreSQL.");

        assertThat(run(PersistenceRules.MIGRATIONS, input("app.py"))).isEmpty();
    }

    @Test
    void persistenceToolingAndDataAccessFiles() {
        AnalysisInput in = input(Fixtures.inventory("requirements.txt", "app/repositories/orders.py", "app/crud.py"),
                Map.of("requirements.txt", "sqlalchemy\n"));
        assertThat(only(PersistenceRules.TOOLING, in).message()).isEqualTo("Persistence tooling detected: SQLAlchemy.");
        assertThat(only(PersistenceRules.DATA_ACCESS_LAYER, in).evidence()).containsEntry("fileCount", 2);
        assertThat(run(PersistenceRules.DATA_ACCESS_LAYER, input("app/main.py"))).isEmpty();
    }

    // ---- Analyzer ----------------------------------------------------
    @Test
    void registryHasUniqueIdsAndEverySignalIsWellFormed() {
        assertThat(DeterministicAnalyzer.RULES).hasSize(23);
        assertThat(new HashSet<>(DeterministicAnalyzer.RULES.stream().map(AnalysisRule::id).toList())).hasSize(23);
        assertThat(DeterministicAnalyzer.RULES).allSatisfy(r -> assertThat(r.id()).startsWith(r.category().name() + "."));

        DeterministicAnalysis a = new DeterministicAnalyzer().analyze(input("README.md", "app/main.py", ".env", "Dockerfile"));
        assertThat(a.rulesRun()).hasSize(23);
        assertThat(a.signals()).isNotEmpty().allSatisfy(s -> {
            assertThat(s.machineDetectable()).isTrue();
            assertThat(s.evidence()).isNotNull();
            assertThat(s.ruleId()).startsWith(s.category().name());
        });
    }

    @Test
    void sameInputSameSignals() {
        AnalysisInput in = input(Fixtures.inventory("package.json", "src/index.ts", "src/routes/a.ts", "tests/a.test.ts"),
                Map.of("package.json", "{\"dependencies\":{\"express\":\"^5\"}}"));
        DeterministicAnalyzer analyzer = new DeterministicAnalyzer();
        assertThat(analyzer.analyze(in)).isEqualTo(analyzer.analyze(in));
    }
}
