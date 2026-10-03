package com.engineeringlens.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.common.InventoryFile;
import com.engineeringlens.analysis.common.SourceTexts;
import com.engineeringlens.analysis.profile.RepositoryProfile;
import com.engineeringlens.analysis.profile.RepositoryProfile.Detection;
import com.engineeringlens.analysis.profile.RepositoryProfile.StructureSignal;
import com.engineeringlens.analysis.profile.RepositoryProfiler;

class RepositoryProfilerTest {

    private final RepositoryProfiler profiler = new RepositoryProfiler();
    private static final RepositoryProfiler.ProjectFacts FACTS =
            new RepositoryProfiler.ProjectFacts("demo", "asha", "main", "PUBLIC", "c0ffee", true);

    private RepositoryProfile profile(List<InventoryFile> inventory, Map<String, String> texts) {
        return profiler.profile(FACTS, inventory, SourceTexts.of(texts));
    }

    private static Detection detection(List<Detection> list, String name) {
        return list.stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not detected in " + list));
    }

    private static List<String> names(List<Detection> list) {
        return list.stream().map(Detection::name).toList();
    }

    @Test
    void pythonFastApiServiceWithPostgresDockerCiAndTests() {
        List<InventoryFile> inventory = Fixtures.inventory(
                "README.md", "requirements.txt", "app/main.py", "app/routers/orders.py", "app/services/order_service.py",
                "app/models.py", "alembic.ini", "alembic/versions/001_init.py", "tests/test_orders.py", "tests/conftest.py",
                "Dockerfile", "docker-compose.yml", ".github/workflows/ci.yml", ".env.example", "docs/architecture.md");
        RepositoryProfile p = profile(inventory, Map.of(
                "requirements.txt", "fastapi==0.110\nuvicorn\npsycopg[binary]>=3\nSQLAlchemy\nalembic\npytest\n# tools\n-r dev.txt\n",
                "docker-compose.yml", "services:\n  db:\n    image: postgres:16\n",
                ".env.example", "DATABASE_URL=postgresql://user:pass@localhost/app\n"));

        assertThat(p.profileSchemaVersion()).isEqualTo(1);
        Detection fastapi = detection(p.frameworks(), "FastAPI");
        assertThat(fastapi.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(fastapi.evidence()).containsExactly("requirements.txt declares fastapi");

        Detection postgres = detection(p.databases(), "PostgreSQL");
        assertThat(postgres.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(postgres.evidence()).contains("requirements.txt declares psycopg", "docker-compose.yml runs a postgres image",
                ".env.example contains a PostgreSQL connection setting");
        assertThat(postgres.evidence()).noneMatch(e -> e.contains("user:pass")); // values are never copied

        assertThat(names(p.persistence())).containsExactly("Alembic", "SQLAlchemy");
        assertThat(names(p.packageManagers())).containsExactly("pip");
        assertThat(p.manifests()).singleElement().satisfies(m -> assertThat(m.declaredDependencies()).isEqualTo(6));

        assertThat(p.testing().testsDetected()).isTrue();
        assertThat(p.testing().testFileCount()).isEqualTo(1); // conftest.py is test configuration, not a test
        assertThat(p.testing().testDirectories()).containsExactly("tests");
        assertThat(names(p.testing().frameworks())).containsExactly("pytest");

        assertThat(p.deployment().dockerfile()).isTrue();
        assertThat(p.deployment().compose()).isTrue();
        assertThat(names(p.deployment().ciProviders())).containsExactly("GitHub Actions");
        assertThat(p.configuration().envExampleFiles()).containsExactly(".env.example");
        assertThat(p.documentation().readme()).isTrue();
        assertThat(p.documentation().docsDirectory()).isTrue();
        assertThat(p.documentation().architectureDocs()).containsExactly("docs/architecture.md");
        assertThat(p.entrypoints()).containsExactly("app/main.py");
        assertThat(p.structureSignals()).extracting(StructureSignal::signal).contains("Route/controller directory",
                "Service directory", "Test directory", "Database migration directory", "CI workflow directory");
    }

    @Test
    void reactTypeScriptAppWithVitest() {
        List<InventoryFile> inventory = Fixtures.inventory("package.json", "package-lock.json", "index.html", "src/main.tsx",
                "src/App.tsx", "src/App.test.tsx", "src/components/Button.tsx", "vite.config.ts", "tsconfig.json");
        RepositoryProfile p = profile(inventory, Map.of("package.json", """
                {"dependencies":{"react":"^19.0.0","react-dom":"^19.0.0"},
                 "devDependencies":{"vite":"^8","vitest":"^5","@testing-library/react":"^16","typescript":"~6"}}"""));

        assertThat(detection(p.frameworks(), "React").evidence()).containsExactly("package.json declares react");
        assertThat(names(p.testing().frameworks())).containsExactly("Testing Library", "Vitest");
        assertThat(detection(p.packageManagers(), "npm").confidence()).isEqualTo(Confidence.HIGH);
        assertThat(p.manifests().get(0).declaredDependencies()).isEqualTo(6);
        assertThat(p.databases()).isEmpty();
        assertThat(p.entrypoints()).containsExactly("src/main.tsx");
        assertThat(p.languages().get(0).name()).isEqualTo("TypeScript");
        assertThat(p.deployment().dockerfile()).isFalse();
        assertThat(p.deployment().ci()).isFalse();
    }

    @Test
    void springBootMavenServiceWithJpaFlywayAndJUnit() {
        String base = "src/main/java/com/demo/";
        List<InventoryFile> inventory = Fixtures.inventory("pom.xml", base + "DemoApplication.java",
                base + "controller/OrderController.java", base + "service/OrderService.java",
                base + "repository/OrderRepository.java", "src/main/resources/application.properties",
                "src/main/resources/db/migration/V1__init.sql", "src/test/java/com/demo/OrderServiceTest.java");
        RepositoryProfile p = profile(inventory, Map.of(
                "pom.xml", """
                        <project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId></parent>
                        <dependencies>
                          <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
                          <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
                          <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
                          <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
                          <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>
                          <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId></dependency>
                        </dependencies></project>""",
                "src/main/resources/application.properties", "spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/demo}\n"));

        assertThat(names(p.frameworks())).containsExactly("Spring Boot", "Spring MVC");
        assertThat(names(p.databases())).containsExactly("H2", "PostgreSQL");
        assertThat(detection(p.databases(), "PostgreSQL").evidence()).contains(
                "pom.xml declares org.postgresql:postgresql",
                "src/main/resources/application.properties contains a PostgreSQL connection setting");
        assertThat(names(p.persistence())).containsExactly("Flyway", "Spring Data JPA");
        assertThat(names(p.packageManagers())).containsExactly("Maven");
        assertThat(names(p.testing().frameworks())).containsExactly("JUnit");
        assertThat(p.testing().testFileCount()).isEqualTo(1);
        assertThat(p.manifests().get(0).declaredDependencies()).isEqualTo(6);
        assertThat(p.entrypoints()).containsExactly(base + "DemoApplication.java");
        assertThat(p.structureSignals()).extracting(StructureSignal::signal).contains("Route/controller directory",
                "Service directory", "Repository/DAO directory", "Database migration directory", "Test directory");
    }

    @Test
    void wellKnownFilesAloneGiveMediumConfidence() {
        RepositoryProfile p = profile(Fixtures.inventory("manage.py", "shop/settings.py", "shop/models.py"),
                Map.of("shop/settings.py", "DATABASES = {'default': {'ENGINE': 'django.db.backends.sqlite3'}}"));

        assertThat(detection(p.frameworks(), "Django").confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(detection(p.databases(), "SQLite").confidence()).isEqualTo(Confidence.HIGH);
        assertThat(names(p.persistence())).containsExactly("Django ORM");
    }

    @Test
    void readmeProseIsNeverEvidence() {
        RepositoryProfile p = profile(Fixtures.inventory("README.md", "main.go"),
                Map.of("README.md", "We might use postgres://localhost later, or MongoDB, or Redis."));
        assertThat(p.databases()).isEmpty();
        assertThat(p.frameworks()).isEmpty();
    }

    @Test
    void npmWithoutALockfileIsOnlyAGuess() {
        RepositoryProfile p = profile(Fixtures.inventory("package.json", "index.js"), Map.of("package.json", "{\"dependencies\":{}}"));
        Detection npm = detection(p.packageManagers(), "npm");
        assertThat(npm.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(npm.evidence().get(0)).contains("no lockfile");
    }

    @Test
    void brokenManifestDoesNotBreakProfiling() {
        RepositoryProfile p = profile(Fixtures.inventory("package.json"), Map.of("package.json", "{ not json"));
        assertThat(p.manifests().get(0).declaredDependencies()).isNull();
        assertThat(p.frameworks()).isEmpty();
    }

    @Test
    void languageSharesAreFileCountsOverRelevantFiles() {
        List<InventoryFile> inventory = Fixtures.with(Fixtures.inventory("a.py", "b.py", "c.ts", "notes.txt"),
                Fixtures.ignored("node_modules/x/index.js", "Dependency directory"));
        RepositoryProfile p = profile(inventory, Map.of());
        assertThat(p.inventory().totalFiles()).isEqualTo(5);
        assertThat(p.inventory().relevantFiles()).isEqualTo(4);
        assertThat(p.languages()).extracting(RepositoryProfile.LanguageShare::name, RepositoryProfile.LanguageShare::percentage)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Python", 50.0), org.assertj.core.groups.Tuple.tuple("TypeScript", 25.0));
    }

    @Test
    void picksManifestsAndConfigToReadButNeverSecrets() {
        List<InventoryFile> inventory = Fixtures.with(Fixtures.inventory("frontend/package.json", "package.json", ".env",
                ".env.local", ".env.example", "docker-compose.yml", "backend/requirements.txt", "src/app.py",
                "config/application.yml"),
                Fixtures.ignored("node_modules/react/package.json", "Dependency directory"),
                Fixtures.file("huge/package.json", 5_000_000));

        List<String> toRead = RepositoryProfiler.filesToRead(inventory, 20, 200_000);

        assertThat(toRead).containsExactly("package.json", "backend/requirements.txt", "frontend/package.json",
                "docker-compose.yml", "config/application.yml", ".env.example");
        assertThat(RepositoryProfiler.filesToRead(inventory, 2, 200_000)).containsExactly("package.json", "backend/requirements.txt");
    }

    @Test
    void pyprojectExtrasDoNotEndTheDependencyList() {
        RepositoryProfile p = profile(Fixtures.inventory("backend/pyproject.toml"), Map.of("backend/pyproject.toml", """
                [project]
                name = "app"
                dependencies = [
                    "fastapi[standard]>=0.141.1,<1.0.0",
                    "alembic>=1.19.1,<2.0.0",
                    "psycopg[binary]>=3.3.6,<4.0.0",
                    "sqlmodel>=0.0.39,<1.0.0",
                ]

                [dependency-groups]
                dev = ["pytest<10.0.0,>=7.4.3"]
                """));
        assertThat(names(p.frameworks())).containsExactly("FastAPI");
        assertThat(names(p.persistence())).containsExactly("Alembic", "SQLModel");
        assertThat(detection(p.persistence(), "Alembic").confidence()).isEqualTo(Confidence.HIGH);
        assertThat(names(p.testing().frameworks())).containsExactly("pytest");
        assertThat(p.manifests().get(0).declaredDependencies()).isEqualTo(5);
    }
}
