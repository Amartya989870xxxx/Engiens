package com.engineeringlens.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.ReviewDimension;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.context.AnalysisContext;
import com.engineeringlens.analysis.context.ContextBuilder;
import com.engineeringlens.analysis.context.ContextFile;
import com.engineeringlens.analysis.context.ContextFile.ContentStatus;
import com.engineeringlens.analysis.context.ContextProperties;
import com.engineeringlens.analysis.context.ContextSelector;
import com.engineeringlens.analysis.context.DeveloperProfile;
import com.engineeringlens.analysis.deterministic.AnalysisInput;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.deterministic.DeterministicAnalyzer;
import com.engineeringlens.analysis.profile.RepositoryProfile;
import com.engineeringlens.analysis.profile.RepositoryProfiler;
import com.engineeringlens.analysis.source.RunFileCache;
import com.engineeringlens.analysis.source.SourceSnapshot;
import com.engineeringlens.common.ApiException;

class ContextBuilderTest {

    private final ContextSelector selector = new ContextSelector();
    private final ContextBuilder builder = new ContextBuilder();
    private static final ContextProperties LIMITS = ContextProperties.defaults();

    /** A FastAPI service with every kind of file a reviewer might want. */
    private static final List<InventoryFile> SERVICE = Fixtures.with(Fixtures.inventory(
            "README.md", "requirements.txt", "poetry.lock", "Dockerfile", "docker-compose.yml", ".github/workflows/ci.yml",
            ".env.example", ".env", "app/main.py", "app/routers/orders.py", "app/services/order_service.py",
            "app/repositories/order_repository.py", "app/models.py", "app/schemas.py", "app/core/config.py",
            "app/core/logging.py", "app/middleware/errors.py", "app/auth/jwt.py", "app/utils/dates.py",
            "migrations/001_init.sql", "tests/test_orders.py", "tests/conftest.py", "docs/notes.md"),
            Fixtures.ignored("node_modules/x/index.js", "Dependency directory"));

    /** Serves files from a map, recording what was requested; unknown paths are 404s. */
    static class FakeSnapshot implements SourceSnapshot {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        FakeSnapshot with(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public String commitSha() {
            return "c0ffee";
        }

        @Override
        public boolean pinnedAtImport() {
            return true;
        }

        @Override
        public byte[] read(String path) {
            requested.add(path);
            byte[] b = files.get(path);
            if (b == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_FILE_NOT_FOUND", "File not found at the imported commit");
            }
            return b;
        }
    }

    private static FakeSnapshot everyFileExists(List<InventoryFile> inventory) {
        FakeSnapshot s = new FakeSnapshot();
        inventory.forEach(f -> s.with(f.path(), "# " + f.path() + "\nprint('ok')\n"));
        return s;
    }

    private static List<String> picked(ContextSelector.Selection sel, ReviewDimension d) {
        return sel.dimensions().get(d).stream().map(ContextSelector.Pick::path).toList();
    }

    // ---- selection -----------------------------------------------------------------------------

    @Test
    void eachDimensionSelectsTheFilesThatEvidenceIt() {
        ContextSelector.Selection sel = selector.select(SERVICE, LIMITS);

        assertThat(picked(sel, ReviewDimension.ARCHITECTURE)).startsWith("app/main.py")
                .contains("app/routers/orders.py", "app/services/order_service.py", "app/repositories/order_repository.py");
        assertThat(picked(sel, ReviewDimension.TESTING)).startsWith("tests/test_orders.py", "tests/conftest.py");
        assertThat(picked(sel, ReviewDimension.PERSISTENCE)).contains("migrations/001_init.sql",
                "app/repositories/order_repository.py", "app/models.py");
        assertThat(picked(sel, ReviewDimension.PRODUCTION_READINESS)).contains("Dockerfile", "docker-compose.yml",
                ".github/workflows/ci.yml", "app/core/config.py", "app/core/logging.py", ".env.example");
        assertThat(picked(sel, ReviewDimension.SECURITY)).startsWith("app/auth/jwt.py");
        assertThat(picked(sel, ReviewDimension.ERROR_HANDLING)).startsWith("app/middleware/errors.py");
    }

    @Test
    void secretsLockfilesAndIgnoredFilesAreNeverSelected() {
        ContextSelector.Selection sel = selector.select(SERVICE, LIMITS);
        List<String> all = sel.files().stream().map(c -> c.file().path()).toList();
        assertThat(all).doesNotContain(".env", "poetry.lock", "node_modules/x/index.js").contains(".env.example");
    }

    @Test
    void everySelectedFileSaysWhy() {
        ContextSelector.Selection sel = selector.select(SERVICE, LIMITS);
        assertThat(sel.files()).allSatisfy(c -> {
            assertThat(c.reasons()).isNotEmpty();
            assertThat(c.dimensions()).isNotEmpty();
        });
        sel.dimensions().values().forEach(picks -> assertThat(picks).allSatisfy(p -> assertThat(p.reasons()).isNotEmpty()));
        ContextSelector.Pick service = sel.dimensions().get(ReviewDimension.ARCHITECTURE).stream()
                .filter(p -> p.path().equals("app/services/order_service.py")).findFirst().orElseThrow();
        assertThat(service.reasons()).containsExactly("service-layer file", "relevant to architecture review");
        assertThat(service.relevance()).isEqualTo("HIGH");
    }

    @Test
    void aFileUsedByManyDimensionsAppearsOnce() {
        ContextSelector.Selection sel = selector.select(SERVICE, LIMITS);
        List<String> paths = sel.files().stream().map(c -> c.file().path()).toList();
        assertThat(paths).doesNotHaveDuplicates();
        ContextSelector.Candidate service = sel.files().stream()
                .filter(c -> c.file().path().equals("app/services/order_service.py")).findFirst().orElseThrow();
        assertThat(service.dimensions()).contains(ReviewDimension.ARCHITECTURE, ReviewDimension.CODE_QUALITY,
                ReviewDimension.PERFORMANCE);
    }

    @Test
    void selectionIsDeterministicAndRespectsCaps() {
        List<InventoryFile> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(Fixtures.file("app/services/s" + i + ".py", 1_000 + i));
        }
        ContextProperties tight = new ContextProperties(3, 5, 16384, 262144, 262144, 20, 200000);
        ContextSelector.Selection a = selector.select(many, tight);
        assertThat(a).isEqualTo(selector.select(many, tight));
        assertThat(a.dimensions().values()).allSatisfy(picks -> assertThat(picks.size()).isLessThanOrEqualTo(3));
        assertThat(a.files()).hasSizeLessThanOrEqualTo(5);
        // Ties break by size (larger first), then path.
        assertThat(picked(a, ReviewDimension.ARCHITECTURE)).containsExactly("app/services/s29.py", "app/services/s28.py",
                "app/services/s27.py");
    }

    // ---- fetching ------------------------------------------------------------------------------

    @Test
    void largeFilesAreTruncatedAtALineBreakAndSayHowMuchWasKept() {
        List<InventoryFile> inventory = Fixtures.inventory("app/services/big_service.py");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            big.append("line ").append(i).append(" of a long service file\n");
        }
        FakeSnapshot snapshot = new FakeSnapshot().with("app/services/big_service.py", big.toString());

        ContextBuilder.Contents c = builder.fetch(selector.select(inventory, LIMITS), new RunFileCache(snapshot), LIMITS);

        ContextFile f = c.files().get(0);
        assertThat(f.contentStatus()).isEqualTo(ContentStatus.TRUNCATED);
        assertThat(f.includedBytes()).isLessThanOrEqualTo(LIMITS.maxFileBytes());
        assertThat(f.originalBytes()).isEqualTo(big.length());
        assertThat(f.note()).startsWith("First 16 KB of ").endsWith("(cut at a line break)");
        assertThat(c.included().get("app/services/big_service.py")).endsWith("\n");
    }

    @Test
    void totalBudgetIsEnforcedAndOverflowIsRecorded() {
        List<InventoryFile> inventory = new ArrayList<>();
        FakeSnapshot snapshot = new FakeSnapshot();
        for (int i = 0; i < 6; i++) {
            String path = "app/services/s" + i + ".py";
            inventory.add(Fixtures.file(path, 10_000));
            snapshot.with(path, "x = 1\n".repeat(1_700)); // ~10 KB each
        }
        ContextProperties budget = new ContextProperties(8, 40, 16384, 262144, 25_000, 20, 200000);

        ContextBuilder.Contents c = builder.fetch(selector.select(inventory, budget), new RunFileCache(snapshot), budget);

        long total = c.files().stream().mapToLong(ContextFile::includedBytes).sum();
        assertThat(total).isLessThanOrEqualTo(25_000);
        assertThat(c.files()).filteredOn(f -> f.contentStatus() == ContentStatus.SKIPPED)
                .isNotEmpty().allSatisfy(f -> assertThat(f.note()).isEqualTo("Context size budget reached"));
    }

    @Test
    void unreadableFilesAreSkippedWithAReasonInsteadOfFailingTheRun() {
        List<InventoryFile> inventory = Fixtures.with(Fixtures.inventory("app/services/a.py", "app/services/gone.py"),
                Fixtures.file("app/services/huge.py", 900_000));
        FakeSnapshot snapshot = new FakeSnapshot().with("app/services/a.py", "ok = True\n");
        snapshot.files.put("app/services/huge.py", new byte[] { 1 });

        ContextBuilder.Contents c = builder.fetch(selector.select(inventory, LIMITS), new RunFileCache(snapshot), LIMITS);

        Map<String, ContextFile> byPath = new HashMap<>();
        c.files().forEach(f -> byPath.put(f.path(), f));
        assertThat(byPath.get("app/services/a.py").contentStatus()).isEqualTo(ContentStatus.INCLUDED);
        assertThat(byPath.get("app/services/gone.py").note()).isEqualTo("File not found at the analysed commit");
        assertThat(byPath.get("app/services/huge.py").note()).isEqualTo("Larger than the 256 KB fetch limit; not downloaded");
        assertThat(snapshot.requested).doesNotContain("app/services/huge.py"); // never downloaded
    }

    @Test
    void binaryAndNonUtf8ContentIsSkipped() {
        List<InventoryFile> inventory = Fixtures.inventory("app/services/a.py", "app/services/b.py");
        FakeSnapshot snapshot = new FakeSnapshot();
        snapshot.files.put("app/services/a.py", new byte[] { 'a', 0, 'b' });
        snapshot.files.put("app/services/b.py", new byte[] { (byte) 0xC3, (byte) 0x28 });

        ContextBuilder.Contents c = builder.fetch(selector.select(inventory, LIMITS), new RunFileCache(snapshot), LIMITS);

        assertThat(c.files()).extracting(ContextFile::note).containsExactlyInAnyOrder("Binary content", "Not valid UTF-8 text");
        assertThat(c.included()).isEmpty();
    }

    @Test
    void filesContainingCredentialsAreWithheldButStillFlagged() {
        String token = "ghp_" + "Z9y8X7w6V5u4T3s2R1q0P9o8N7m6L5k4J3i2";
        List<InventoryFile> inventory = Fixtures.inventory("app/core/config.py", "app/services/a.py");
        FakeSnapshot snapshot = new FakeSnapshot().with("app/core/config.py", "TOKEN = '" + token + "'\n")
                .with("app/services/a.py", "ok = True\n");

        ContextSelector.Selection sel = selector.select(inventory, LIMITS);
        ContextBuilder.Contents c = builder.fetch(sel, new RunFileCache(snapshot), LIMITS);
        ContextFile config = c.files().stream().filter(f -> f.path().equals("app/core/config.py")).findFirst().orElseThrow();

        assertThat(config.contentStatus()).isEqualTo(ContentStatus.WITHHELD);
        assertThat(c.included()).doesNotContainKey("app/core/config.py");
        assertThat(c.included().values()).noneMatch(t -> t.contains(token));
        // The rules still see it, so the problem is reported rather than silently dropped.
        assertThat(c.fullTexts().get("app/core/config.py")).contains(token);
    }

    // ---- assembly ------------------------------------------------------------------------------

    @Test
    void developerProfileIsMetadataOnlyAndNeverChangesSignals() {
        FakeSnapshot snapshot = everyFileExists(SERVICE);
        DeveloperProfile student = new DeveloperProfile("UNDERGRADUATE", 2, null, List.of("Python"), List.of(), List.of(),
                List.of(), "Write cleaner code");
        DeveloperProfile senior = new DeveloperProfile("PROFESSIONAL", null, "OVER_TEN_YEARS", List.of("Go", "Java"),
                List.of("Spring Boot"), List.of("PostgreSQL"), List.of("System design"), null);

        AnalysisContext a = prepare(snapshot, student);
        AnalysisContext b = prepare(snapshot, senior);

        assertThat(a.analysis()).isEqualTo(b.analysis());
        assertThat(a.profile()).isEqualTo(b.profile());
        assertThat(a.manifest().files()).isEqualTo(b.manifest().files());
        assertThat(a.developer()).isEqualTo(student);
        assertThat(b.developer()).isEqualTo(senior);
    }

    @Test
    void contextAnswersPerDimensionQuestionsWithoutGoingBackToGitHub() {
        AnalysisContext ctx = prepare(everyFileExists(SERVICE), null);

        AnalysisContext.DimensionEvidence testing = ctx.evidence(ReviewDimension.TESTING);
        assertThat(testing.files()).extracting(e -> e.file().path()).startsWith("tests/test_orders.py");
        assertThat(testing.files().get(0).text()).startsWith("# tests/test_orders.py");
        assertThat(testing.signals()).isNotEmpty().allSatisfy(s -> assertThat(s.category()).isEqualTo(ReviewDimension.TESTING));
        assertThat(ctx.contextSchemaVersion()).isEqualTo(1);
        assertThat(ctx.manifest().commitSha()).isEqualTo("c0ffee");
        assertThat(ctx.manifest().stats().filesSelected()).isEqualTo(ctx.manifest().files().size());
        // Every included file's hash matches the exact text a reviewer would see.
        ctx.manifest().files().stream().filter(f -> f.sha256() != null)
                .forEach(f -> assertThat(f.includedBytes()).isEqualTo(ctx.contents().get(f.path()).getBytes(StandardCharsets.UTF_8).length));
    }

    private AnalysisContext prepare(FakeSnapshot snapshot, DeveloperProfile developer) {
        RunFileCache cache = new RunFileCache(snapshot);
        SourceTexts profileTexts = new SourceTexts();
        RepositoryProfiler.filesToRead(SERVICE, 20, 200_000).forEach(p -> profileTexts.put(p, cache.fetch(p).text()));
        RepositoryProfile profile = new RepositoryProfiler().profile(
                new RepositoryProfiler.ProjectFacts("svc", "asha", "main", "PUBLIC", "c0ffee", true), SERVICE, profileTexts);
        ContextSelector.Selection sel = selector.select(SERVICE, LIMITS);
        ContextBuilder.Contents contents = builder.fetch(sel, cache, LIMITS);
        Map<String, String> ruleTexts = new HashMap<>(profileTexts.all());
        ruleTexts.putAll(contents.fullTexts().all());
        DeterministicAnalysis analysis = new DeterministicAnalyzer().analyze(new AnalysisInput(SERVICE, profile, SourceTexts.of(ruleTexts)));
        return builder.assemble(profile, analysis, sel, contents, developer, "c0ffee", true, LIMITS);
    }

    @Test
    void toolingConfigIsNotPersistenceOrProductionEvidence() {
        List<InventoryFile> frontend = Fixtures.inventory("package.json", "tsconfig.app.json", ".eslintrc.cjs",
                "tailwind.config.cjs", "vite.config.ts", "src/main.tsx", "src/api/client.ts", "src/features/users/api.ts",
                ".github/workflows/ci.yml");
        ContextSelector.Selection sel = selector.select(frontend, LIMITS);

        assertThat(picked(sel, ReviewDimension.PERSISTENCE)).isEmpty();
        assertThat(picked(sel, ReviewDimension.PRODUCTION_READINESS))
                .doesNotContain("tsconfig.app.json", ".eslintrc.cjs", "tailwind.config.cjs", "vite.config.ts")
                .contains(".github/workflows/ci.yml", "package.json");
        assertThat(picked(sel, ReviewDimension.CODE_QUALITY)).contains(".eslintrc.cjs");
    }

    @Test
    void databaseConfigIsPersistenceEvidence() {
        ContextSelector.Selection sel = selector.select(Fixtures.inventory("src/main/resources/application.yml",
                "app/core/db.py", "prisma/schema.prisma", "vite.config.ts"), LIMITS);
        assertThat(picked(sel, ReviewDimension.PERSISTENCE))
                .contains("src/main/resources/application.yml", "app/core/db.py", "prisma/schema.prisma")
                .doesNotContain("vite.config.ts");
    }

    @Test
    void mocksAndServedAssetsAreNotApplicationCode() {
        ContextSelector.Selection sel = selector.select(Fixtures.inventory("src/testing/mocks/db.ts",
                "public/mockServiceWorker.js", "src/features/users/api.ts", "src/main.tsx"), LIMITS);
        assertThat(picked(sel, ReviewDimension.PERSISTENCE)).isEmpty();
        assertThat(picked(sel, ReviewDimension.TESTING)).contains("src/testing/mocks/db.ts");
        assertThat(sel.files()).extracting(c -> c.file().path()).doesNotContain("public/mockServiceWorker.js");
    }
}
