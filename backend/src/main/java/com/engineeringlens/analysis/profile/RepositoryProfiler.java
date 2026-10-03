package com.engineeringlens.analysis.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.RepoPaths;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.profile.Manifests.Manifest;
import com.engineeringlens.analysis.profile.RepositoryProfile.Detection;
import com.engineeringlens.analysis.profile.RepositoryProfile.StructureSignal;

/**
 * Phase 4A: describes what kind of project a repository is. Two pure steps, so both are testable
 * without GitHub: {@link #filesToRead} picks the few manifest/config files worth fetching, and
 * {@link #profile} builds the profile from the inventory plus whatever of those files was read.
 * README prose is never used as evidence.
 */
@Component
public class RepositoryProfiler {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_EVIDENCE = 5;
    private static final int MAX_LISTED = 10;

    /** Identity of the snapshot being profiled. */
    public record ProjectFacts(String name, String owner, String defaultBranch, String visibility, String commitSha,
            boolean commitPinnedAtImport) {
    }

    // ---- step 1: which files to fetch ----------------------------------------------------------

    /**
     * Manifests first, then compose files, config and env templates; shallow paths before deep ones.
     * Secret-like files (.env, keys) are never candidates.
     */
    public static List<String> filesToRead(List<InventoryFile> inventory, int maxFiles, long maxBytes) {
        return inventory.stream()
                .filter(f -> !f.ignored() && f.sizeBytes() <= maxBytes && !RepoPaths.isSecretLike(f.path()))
                .filter(f -> readPriority(f.path()) < Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt((InventoryFile f) -> readPriority(f.path()))
                        .thenComparingInt(f -> RepoPaths.depth(f.path()))
                        .thenComparing(InventoryFile::path))
                .limit(maxFiles)
                .map(InventoryFile::path)
                .toList();
    }

    private static final Pattern PROFILE_CONFIG = Pattern.compile(
            "application(-[\\w-]+)?\\.(properties|ya?ml)|settings\\.py|schema\\.prisma|appsettings(\\.[\\w-]+)?\\.json");

    private static int readPriority(String path) {
        if (RepoPaths.isManifest(path)) {
            return 0;
        }
        if (RepoPaths.isComposeFile(path)) {
            return 1;
        }
        if (PROFILE_CONFIG.matcher(RepoPaths.lowerName(path)).matches() && !path.toLowerCase(Locale.ROOT).contains("/test/")) {
            return 2;
        }
        if (RepoPaths.isEnvTemplate(path)) {
            return 3;
        }
        return Integer.MAX_VALUE;
    }

    // ---- step 2: build the profile -------------------------------------------------------------

    public RepositoryProfile profile(ProjectFacts facts, List<InventoryFile> inventory, SourceTexts texts) {
        List<InventoryFile> relevant = inventory.stream().filter(f -> !f.ignored()).toList();
        List<Manifest> manifests = texts.all().entrySet().stream()
                .filter(e -> RepoPaths.isManifest(e.getKey()))
                .map(e -> Manifests.parse(e.getKey(), e.getValue()))
                .toList();

        Detections frameworks = frameworks(manifests, relevant);
        RepositoryProfile.Testing testing = testing(manifests, relevant);

        return new RepositoryProfile(
                SCHEMA_VERSION,
                new RepositoryProfile.Project(facts.name(), facts.owner(), facts.defaultBranch(), facts.visibility(),
                        facts.commitSha(), facts.commitPinnedAtImport()),
                new RepositoryProfile.InventorySummary(inventory.size(), relevant.size(), inventory.size() - relevant.size(),
                        inventory.stream().mapToLong(InventoryFile::sizeBytes).sum()),
                languages(relevant),
                frameworks.toList(),
                databases(manifests, inventory, texts).toList(),
                persistence(manifests, relevant, frameworks).toList(),
                packageManagers(manifests, inventory).toList(),
                manifests.stream()
                        .map(m -> new RepositoryProfile.ManifestSummary(m.path(), m.ecosystem(), m.declaredCount()))
                        .toList(),
                testing,
                deployment(relevant),
                configuration(relevant),
                documentation(relevant),
                structure(relevant),
                relevant.stream().filter(f -> RepoPaths.isEntrypoint(f.path(), f.language()))
                        .map(InventoryFile::path).limit(MAX_LISTED).toList(),
                texts.unread().entrySet().stream()
                        .map(e -> new RepositoryProfile.UnreadFile(e.getKey(), e.getValue()))
                        .toList());
    }

    static List<RepositoryProfile.LanguageShare> languages(List<InventoryFile> relevant) {
        Map<String, Integer> counts = new TreeMap<>();
        relevant.stream().filter(f -> f.language() != null).forEach(f -> counts.merge(f.language(), 1, Integer::sum));
        int total = relevant.size();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new RepositoryProfile.LanguageShare(e.getKey(), e.getValue(),
                        total == 0 ? 0 : Math.round(e.getValue() * 1000.0 / total) / 10.0))
                .toList();
    }

    // ---- frameworks ----------------------------------------------------------------------------

    private static Detections frameworks(List<Manifest> manifests, List<InventoryFile> files) {
        Detections d = new Detections(manifests, files);
        d.dependency("FastAPI", "python", "fastapi");
        d.dependency("Django", "python", "django");
        d.file("Django", p -> RepoPaths.lowerName(p).equals("manage.py"), "manage.py present");
        d.dependency("Flask", "python", "flask");
        d.dependency("React", "npm", "react");
        d.dependency("Next.js", "npm", "next");
        d.file("Next.js", p -> RepoPaths.lowerName(p).matches("next\\.config\\.(js|mjs|ts|cjs)"), "Next.js config present");
        d.dependency("Express", "npm", "express");
        d.dependency("NestJS", "npm", "@nestjs/core");
        d.file("NestJS", p -> RepoPaths.lowerName(p).equals("nest-cli.json"), "nest-cli.json present");
        d.dependency("Vue", "npm", "vue");
        d.dependency("Angular", "npm", "@angular/core");
        d.file("Angular", p -> RepoPaths.lowerName(p).equals("angular.json"), "angular.json present");
        d.dependency("Svelte", "npm", "svelte");
        d.dependencyMatching("Spring Boot", "jvm", n -> n.startsWith("spring-boot-starter") || n.equals("org.springframework.boot:plugin"),
                "a Spring Boot starter or plugin");
        d.dependencyMatching("Spring MVC", "jvm",
                n -> n.equals("spring-boot-starter-web") || n.equals("spring-boot-starter-webmvc") || n.equals("spring-webmvc"),
                "spring-boot-starter-web(mvc) or spring-webmvc");
        d.dependency("Gin", "go", "github.com/gin-gonic/gin");
        d.dependency("Echo", "go", "github.com/labstack/echo/v4");
        d.dependency("Actix Web", "cargo", "actix-web");
        d.dependency("Axum", "cargo", "axum");
        return d;
    }

    // ---- databases -----------------------------------------------------------------------------

    private record UrlHint(String database, Pattern pattern, String label) {
    }

    private static final List<UrlHint> URL_HINTS = List.of(
            new UrlHint("PostgreSQL", Pattern.compile("jdbc:postgresql:|\\bpostgres(ql)?(\\+\\w+)?://|provider\\s*=\\s*\"postgresql\"|django\\.db\\.backends\\.postgresql"), "a PostgreSQL connection setting"),
            new UrlHint("MySQL", Pattern.compile("jdbc:mysql:|\\bmysql(\\+\\w+)?://|provider\\s*=\\s*\"mysql\"|django\\.db\\.backends\\.mysql"), "a MySQL connection setting"),
            new UrlHint("MariaDB", Pattern.compile("jdbc:mariadb:|\\bmariadb(\\+\\w+)?://"), "a MariaDB connection setting"),
            new UrlHint("MongoDB", Pattern.compile("\\bmongodb(\\+srv)?://|provider\\s*=\\s*\"mongodb\""), "a MongoDB connection setting"),
            new UrlHint("SQLite", Pattern.compile("jdbc:sqlite:|\\bsqlite(\\+\\w+)?:///|provider\\s*=\\s*\"sqlite\"|django\\.db\\.backends\\.sqlite3"), "a SQLite connection setting"),
            new UrlHint("Redis", Pattern.compile("\\brediss?://"), "a Redis connection URL"),
            new UrlHint("H2", Pattern.compile("jdbc:h2:"), "an H2 JDBC URL"));

    private static final Pattern COMPOSE_IMAGE = Pattern.compile(
            "(?m)^\\s*image:\\s*[\"']?(?:docker\\.io/)?(?:library/|bitnami/)?(postgres|mysql|mariadb|mongo|redis)\\b");
    private static final Map<String, String> IMAGE_DATABASE = Map.of("postgres", "PostgreSQL", "mysql", "MySQL",
            "mariadb", "MariaDB", "mongo", "MongoDB", "redis", "Redis");

    private static Detections databases(List<Manifest> manifests, List<InventoryFile> inventory, SourceTexts texts) {
        Detections d = new Detections(manifests, inventory);
        d.dependency("PostgreSQL", "python", "psycopg", "psycopg2", "psycopg2-binary", "psycopg-binary", "asyncpg");
        d.dependency("PostgreSQL", "npm", "pg", "postgres", "pg-promise");
        d.dependency("PostgreSQL", "jvm", "org.postgresql:postgresql", "r2dbc-postgresql");
        d.dependency("PostgreSQL", "go", "github.com/lib/pq", "github.com/jackc/pgx/v5");
        d.dependency("MySQL", "python", "mysqlclient", "pymysql", "mysql-connector-python", "aiomysql");
        d.dependency("MySQL", "npm", "mysql", "mysql2");
        d.dependency("MySQL", "jvm", "mysql-connector-java", "mysql-connector-j");
        d.dependency("MariaDB", "jvm", "mariadb-java-client");
        d.dependency("MongoDB", "python", "pymongo", "motor", "mongoengine", "beanie");
        d.dependency("MongoDB", "npm", "mongodb", "mongoose");
        d.dependency("MongoDB", "jvm", "spring-boot-starter-data-mongodb", "mongodb-driver-sync");
        d.dependency("SQLite", "python", "aiosqlite");
        d.dependency("SQLite", "npm", "sqlite3", "better-sqlite3");
        d.dependency("SQLite", "jvm", "sqlite-jdbc");
        d.dependency("Redis", "python", "redis", "aioredis");
        d.dependency("Redis", "npm", "redis", "ioredis");
        d.dependency("Redis", "jvm", "spring-boot-starter-data-redis", "jedis", "lettuce-core");
        d.dependency("H2", "jvm", "com.h2database:h2");

        // Connection settings in config files: explicit, so HIGH. Only the kind of URL is recorded, never its value.
        texts.all().forEach((path, text) -> {
            // Only configuration counts as evidence: a README sentence mentioning a database proves nothing.
            if (!isConfigEvidence(path)) {
                return;
            }
            for (UrlHint hint : URL_HINTS) {
                if (hint.pattern().matcher(text).find()) {
                    d.add(hint.database(), Confidence.HIGH, path + " contains " + hint.label());
                }
            }
            if (RepoPaths.isComposeFile(path)) {
                var m = COMPOSE_IMAGE.matcher(text);
                while (m.find()) {
                    d.add(IMAGE_DATABASE.get(m.group(1)), Confidence.MEDIUM, path + " runs a " + m.group(1) + " image");
                }
            }
        });
        d.file("SQLite", p -> RepoPaths.lowerName(p).matches(".+\\.(sqlite|sqlite3)"), "SQLite data file in the repository");
        return d;
    }

    private static boolean isConfigEvidence(String path) {
        return RepoPaths.isComposeFile(path) || RepoPaths.isEnvTemplate(path) || RepoPaths.isConfigFile(path)
                || PROFILE_CONFIG.matcher(RepoPaths.lowerName(path)).matches();
    }

    private static Detections persistence(List<Manifest> manifests, List<InventoryFile> files, Detections frameworks) {
        Detections d = new Detections(manifests, files);
        d.dependency("SQLAlchemy", "python", "sqlalchemy");
        d.dependency("SQLModel", "python", "sqlmodel");
        d.dependency("Alembic", "python", "alembic");
        d.file("Alembic", p -> RepoPaths.lowerName(p).equals("alembic.ini") || p.toLowerCase(Locale.ROOT).contains("alembic/versions/"),
                "Alembic configuration or versions present");
        d.dependency("Peewee", "python", "peewee");
        d.dependency("Tortoise ORM", "python", "tortoise-orm");
        if (frameworks.has("Django")) {
            d.file("Django ORM", p -> RepoPaths.lowerName(p).equals("models.py"), "Django models.py present");
        }
        d.dependency("Prisma", "npm", "prisma", "@prisma/client");
        d.file("Prisma", p -> RepoPaths.lowerName(p).equals("schema.prisma"), "schema.prisma present");
        d.dependency("TypeORM", "npm", "typeorm");
        d.dependency("Sequelize", "npm", "sequelize");
        d.dependency("Mongoose", "npm", "mongoose");
        d.dependency("Drizzle", "npm", "drizzle-orm");
        d.dependency("Knex", "npm", "knex");
        d.dependency("Spring Data JPA", "jvm", "spring-boot-starter-data-jpa");
        d.dependency("Hibernate", "jvm", "hibernate-core");
        d.dependency("Spring JDBC", "jvm", "spring-boot-starter-jdbc");
        d.dependency("Flyway", "jvm", "flyway-core", "spring-boot-starter-flyway");
        d.file("Flyway", p -> p.toLowerCase(Locale.ROOT).contains("db/migration/") && RepoPaths.lowerName(p).matches("v\\d+.*__.+\\.sql"),
                "Flyway-style migrations (V1__name.sql) present");
        d.dependency("Liquibase", "jvm", "liquibase-core");
        d.dependency("MyBatis", "jvm", "mybatis", "mybatis-spring-boot-starter");
        d.dependency("GORM", "go", "gorm.io/gorm");
        d.dependency("Diesel", "cargo", "diesel");
        d.dependency("SQLx", "cargo", "sqlx");
        return d;
    }

    // ---- package managers ----------------------------------------------------------------------

    private static Detections packageManagers(List<Manifest> manifests, List<InventoryFile> inventory) {
        Detections d = new Detections(manifests, inventory);
        d.file("npm", p -> RepoPaths.lowerName(p).equals("package-lock.json"), "package-lock.json present");
        d.file("pnpm", p -> RepoPaths.lowerName(p).equals("pnpm-lock.yaml"), "pnpm-lock.yaml present");
        d.file("Yarn", p -> RepoPaths.lowerName(p).equals("yarn.lock"), "yarn.lock present");
        d.file("Bun", p -> RepoPaths.lowerName(p).matches("bun\\.lockb?"), "bun lockfile present");
        boolean jsLockfile = d.has("npm") || d.has("pnpm") || d.has("Yarn") || d.has("Bun");
        if (!jsLockfile) {
            d.file("npm", p -> RepoPaths.lowerName(p).equals("package.json"), "package.json present (no lockfile, so the client is a guess)");
        }
        d.file("pip", RepoPaths::isRequirementsFile, "requirements file present");
        d.file("Poetry", p -> RepoPaths.lowerName(p).equals("poetry.lock"), "poetry.lock present");
        manifests.stream().filter(Manifest::poetry).findFirst()
                .ifPresent(m -> d.add("Poetry", Confidence.HIGH, m.path() + " has a [tool.poetry] section"));
        d.file("uv", p -> RepoPaths.lowerName(p).equals("uv.lock"), "uv.lock present");
        d.file("Pipenv", p -> RepoPaths.lowerName(p).matches("pipfile(\\.lock)?"), "Pipfile present");
        d.file("Maven", p -> RepoPaths.lowerName(p).equals("pom.xml"), "pom.xml present");
        d.file("Gradle", p -> RepoPaths.lowerName(p).startsWith("build.gradle"), "Gradle build file present");
        d.file("Go modules", p -> RepoPaths.lowerName(p).equals("go.mod"), "go.mod present");
        d.file("Cargo", p -> RepoPaths.lowerName(p).equals("cargo.toml"), "Cargo.toml present");
        // A lockfile or build file is the tool's own artifact, so these file hints are strong evidence.
        return d.withFileConfidence(Confidence.HIGH, Set.of("npm"));
    }

    // ---- testing -------------------------------------------------------------------------------

    private static RepositoryProfile.Testing testing(List<Manifest> manifests, List<InventoryFile> relevant) {
        List<String> testFiles = relevant.stream().filter(f -> RepoPaths.isTestFile(f.path(), f.language()))
                .map(InventoryFile::path).toList();
        Detections d = new Detections(manifests, relevant);
        d.dependency("pytest", "python", "pytest");
        d.file("pytest", p -> RepoPaths.lowerName(p).matches("pytest\\.ini|conftest\\.py"), "pytest configuration present");
        d.dependency("Vitest", "npm", "vitest");
        d.dependency("Jest", "npm", "jest");
        d.dependency("Mocha", "npm", "mocha");
        d.dependency("Playwright", "npm", "@playwright/test");
        d.dependency("Cypress", "npm", "cypress");
        d.dependencyMatching("Testing Library", "npm", n -> n.startsWith("@testing-library/"), "an @testing-library package");
        // Spring Boot's test starters bring JUnit Jupiter; Boot 4 splits them per module (spring-boot-starter-webmvc-test…).
        d.dependencyMatching("JUnit", "jvm",
                n -> n.equals("junit") || n.startsWith("junit-jupiter") || n.matches("spring-boot-starter(-[\\w-]+)?-test")
                        || n.equals("testcontainers-junit-jupiter"),
                "JUnit (directly or via a Spring Boot test starter)");
        d.dependency("Mockito", "jvm", "mockito-core");
        d.dependencyMatching("Testcontainers", "jvm", n -> n.startsWith("testcontainers") || n.startsWith("org.testcontainers:")
                || n.equals("spring-boot-testcontainers"), "a Testcontainers module");
        if (testFiles.stream().anyMatch(p -> p.endsWith("_test.go"))) {
            d.add("Go test", Confidence.HIGH, "_test.go files present");
        }
        if (testFiles.stream().anyMatch(p -> p.endsWith(".rs"))) {
            d.add("Cargo test", Confidence.MEDIUM, "Rust files in a tests directory");
        }
        Set<String> testDirs = new TreeSet<>();
        for (String path : testFiles) {
            String[] parts = path.split("/");
            StringBuilder dir = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                dir.append(i == 0 ? "" : "/").append(parts[i]);
                if (RepoPaths.isTestFolder(parts[i])) {
                    testDirs.add(dir.toString());
                    break;
                }
            }
        }
        int relevantCount = relevant.size();
        return new RepositoryProfile.Testing(!testFiles.isEmpty(), testFiles.size(),
                relevantCount == 0 ? 0 : Math.round(testFiles.size() * 1000.0 / relevantCount) / 1000.0,
                d.toList(), testDirs.stream().limit(MAX_LISTED).toList(), testFiles.stream().limit(MAX_LISTED).toList());
    }

    // ---- deployment, configuration, documentation ----------------------------------------------

    private static RepositoryProfile.Deployment deployment(List<InventoryFile> relevant) {
        List<String> dockerfiles = paths(relevant, RepoPaths::isDockerfile);
        List<String> compose = paths(relevant, RepoPaths::isComposeFile);
        Map<String, List<String>> ci = new TreeMap<>();
        relevant.forEach(f -> {
            String provider = RepoPaths.ciProvider(f.path());
            if (provider != null) {
                ci.computeIfAbsent(provider, k -> new ArrayList<>()).add(f.path());
            }
        });
        List<Detection> ciProviders = ci.entrySet().stream()
                .map(e -> new Detection(e.getKey(), Confidence.HIGH, e.getValue().stream().limit(MAX_EVIDENCE).toList()))
                .toList();
        return new RepositoryProfile.Deployment(!dockerfiles.isEmpty(), !compose.isEmpty(), !ciProviders.isEmpty(),
                dockerfiles, compose, ciProviders, paths(relevant, RepoPaths::isDeploymentConfig));
    }

    private static RepositoryProfile.Configuration configuration(List<InventoryFile> relevant) {
        List<String> envExamples = paths(relevant, RepoPaths::isEnvTemplate);
        return new RepositoryProfile.Configuration(!envExamples.isEmpty(), envExamples,
                paths(relevant, p -> RepoPaths.isConfigFile(p) && !RepoPaths.isTestFile(p, null)));
    }

    private static RepositoryProfile.Documentation documentation(List<InventoryFile> relevant) {
        String readme = relevant.stream().map(InventoryFile::path).filter(RepoPaths::isReadme).findFirst().orElse(null);
        boolean docsDir = relevant.stream().anyMatch(f -> {
            List<String> folders = RepoPaths.folders(f.path());
            return !folders.isEmpty() && (folders.get(0).equals("docs") || folders.get(0).equals("doc"));
        });
        return new RepositoryProfile.Documentation(readme != null, readme, docsDir,
                paths(relevant, p -> RepoPaths.lowerName(p).matches("(openapi|swagger)(\\.[\\w-]+)?\\.(ya?ml|json)")),
                paths(relevant, p -> RepoPaths.lowerName(p).startsWith("architecture")
                        || p.toLowerCase(Locale.ROOT).matches("(.*/)?(adr|adrs|decisions)/.+\\.md")));
    }

    private static List<String> paths(List<InventoryFile> files, Predicate<String> test) {
        return files.stream().map(InventoryFile::path).filter(test).limit(MAX_LISTED).toList();
    }

    // ---- structure -----------------------------------------------------------------------------

    private record FolderSignal(String label, Set<String> names, boolean topLevelOnly) {
    }

    private static final List<FolderSignal> FOLDER_SIGNALS = List.of(
            new FolderSignal("Frontend directory", Set.of("frontend", "client", "web", "ui"), true),
            new FolderSignal("Backend directory", Set.of("backend", "server", "api"), true),
            new FolderSignal("Route/controller directory", Set.of("controllers", "controller", "routes", "routers", "router", "handlers", "endpoints"), false),
            new FolderSignal("Service directory", Set.of("services", "service", "usecases"), false),
            new FolderSignal("Repository/DAO directory", Set.of("repositories", "repository", "dao", "daos"), false),
            new FolderSignal("Model/entity directory", Set.of("models", "model", "entities", "entity", "domain"), false),
            new FolderSignal("Schema/DTO directory", Set.of("schemas", "schema", "dto", "dtos"), false),
            new FolderSignal("Middleware directory", Set.of("middleware", "middlewares"), false),
            new FolderSignal("Test directory", Set.of("test", "tests", "__tests__", "spec", "specs", "e2e"), false),
            new FolderSignal("Documentation directory", Set.of("docs", "doc"), true),
            new FolderSignal("Docker directory", Set.of("docker"), false),
            new FolderSignal("Infrastructure directory", Set.of("infra", "infrastructure", "terraform", "k8s", "kubernetes", "helm", "deploy"), false),
            new FolderSignal("UI component directory", Set.of("components"), false),
            new FolderSignal("Shared utility directory", Set.of("utils", "util", "helpers", "helper", "lib", "common", "shared"), false),
            new FolderSignal("Configuration directory", Set.of("config", "configs", "settings"), false));

    static List<StructureSignal> structure(List<InventoryFile> relevant) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (FolderSignal s : FOLDER_SIGNALS) {
            found.put(s.label(), new TreeSet<>());
        }
        found.put("Database migration directory", new TreeSet<>());
        found.put("CI workflow directory", new TreeSet<>());
        for (InventoryFile f : relevant) {
            String[] parts = f.path().split("/");
            for (FolderSignal s : FOLDER_SIGNALS) {
                for (int i = 0; i < parts.length - 1 && (!s.topLevelOnly() || i == 0); i++) {
                    if (s.names().contains(parts[i].toLowerCase(Locale.ROOT))) {
                        found.get(s.label()).add(String.join("/", java.util.Arrays.copyOfRange(parts, 0, i + 1)));
                        break;
                    }
                }
            }
            if (RepoPaths.isMigration(f.path())) {
                found.get("Database migration directory").add(RepoPaths.parent(f.path()));
            }
            if ("GitHub Actions".equals(RepoPaths.ciProvider(f.path()))) {
                found.get("CI workflow directory").add(".github/workflows");
            }
        }
        return found.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .map(e -> new StructureSignal(e.getKey(), e.getValue().stream().limit(3).toList()))
                .toList();
    }

    // ---- detection bookkeeping -----------------------------------------------------------------

    /** Collects evidence per detected name; the strongest evidence decides the confidence. */
    private static final class Detections {

        private final List<Manifest> manifests;
        private final List<InventoryFile> files;
        private final Map<String, Confidence> confidence = new LinkedHashMap<>();
        private final Map<String, List<String>> evidence = new LinkedHashMap<>();
        private final Set<String> fromFilesOnly = new TreeSet<>();

        Detections(List<Manifest> manifests, List<InventoryFile> files) {
            this.manifests = manifests;
            this.files = files;
        }

        void dependency(String name, String ecosystem, String... dependencies) {
            Set<String> wanted = Set.of(dependencies);
            dependencyMatching(name, ecosystem, wanted::contains, null);
        }

        void dependencyMatching(String name, String ecosystem, Predicate<String> match, String description) {
            for (Manifest m : manifests) {
                if (!ecosystem.equals(m.ecosystem())) {
                    continue;
                }
                m.names().stream().filter(match).findFirst()
                        .ifPresent(n -> add(name, Confidence.HIGH, m.path() + " declares " + (description != null ? description : n)));
            }
        }

        void file(String name, Predicate<String> match, String description) {
            files.stream().map(InventoryFile::path).filter(match).findFirst().ifPresent(p -> {
                if (!confidence.containsKey(name)) {
                    fromFilesOnly.add(name);
                }
                add(name, Confidence.MEDIUM, description + ": " + p);
            });
        }

        void add(String name, Confidence c, String text) {
            confidence.merge(name, c, (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
            if (c == Confidence.HIGH) {
                fromFilesOnly.remove(name);
            }
            List<String> list = evidence.computeIfAbsent(name, k -> new ArrayList<>());
            if (!list.contains(text) && list.size() < MAX_EVIDENCE) {
                list.add(text);
            }
        }

        boolean has(String name) {
            return confidence.containsKey(name);
        }

        /** For detections where a file is the tool's own artifact (a lockfile), raise file-only evidence. */
        Detections withFileConfidence(Confidence c, Set<String> except) {
            for (String name : fromFilesOnly) {
                boolean keep = except.contains(name) && evidence.get(name).stream().anyMatch(e -> e.contains("no lockfile"));
                if (!keep) {
                    confidence.put(name, c);
                }
            }
            return this;
        }

        List<Detection> toList() {
            return confidence.keySet().stream()
                    .sorted(Comparator.comparing((String n) -> confidence.get(n)).thenComparing(Comparator.naturalOrder()))
                    .map(n -> new Detection(n, confidence.get(n), List.copyOf(evidence.get(n))))
                    .toList();
        }
    }
}
