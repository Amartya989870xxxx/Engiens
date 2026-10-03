package com.engineeringlens.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileClassifierTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "node_modules/react/index.js, Dependency directory",
            "web/node_modules/a/b.js, Dependency directory",
            "vendor/github.com/pkg/x.go, Dependency directory",
            ".venv/lib/site.py, Dependency directory",
            "dist/bundle.js, Build output",
            "build/classes/App.class, Build output",
            "backend/target/app.jar, Build output",
            ".next/server/page.js, Build output",
            "coverage/lcov.info, Test coverage report",
            "src/__pycache__/app.cpython-312.pyc, Cache",
            ".idea/workspace.xml, Editor settings",
            ".git/HEAD, Version control metadata",
            "docs/logo.png, Image asset",
            "public/hero.JPEG, Image asset",
            "src/assets/icon.svg, Image asset",
            "media/intro.mp4, Video file",
            "sounds/click.wav, Audio file",
            "release/app.zip, Archive",
            "libs/native.dll, Compiled binary",
            "fonts/Inter.woff2, Font file",
            "spec/design.pdf, Document",
            "data/app.sqlite, Data or model file",
            "static/js/app.min.js, Generated or minified file",
            "src/app.js.map, Generated or minified file",
            ".DS_Store, Operating system file",
    })
    void ignoresNoiseWithAReason(String path, String reason) {
        FileClassifier.Classification c = FileClassifier.classify(path, 100);
        assertThat(c.ignored()).isTrue();
        assertThat(c.ignoreReason()).isEqualTo(reason);
    }

    @ParameterizedTest(name = "{0} stays relevant")
    @CsvSource({
            "src/api/orders.ts, TypeScript",
            "src/main/java/App.java, Java",
            "app/main.py, Python",
            "Dockerfile, Docker",
            "docker-compose.yml, YAML",
            "package.json, JSON",
            "package-lock.json, JSON",
            "pnpm-lock.yaml, YAML",
            "yarn.lock, ",
            "pom.xml, XML",
            "build.gradle, Gradle",
            "requirements.txt, ",
            "pyproject.toml, TOML",
            "go.mod, ",
            "Cargo.toml, TOML",
            "tsconfig.json, JSON",
            "README.md, Markdown",
            ".env.example, ",
            "src/main/resources/application.yml, YAML",
            "src/main/resources/application.properties, Properties",
            "src/main/resources/db/migration/V1__init.sql, SQL",
            ".github/workflows/ci.yml, YAML",
            "src/components/Logo.svg, SVG",
            "Makefile, Makefile",
    })
    void keepsSourceAndProjectFiles(String path, String language) {
        FileClassifier.Classification c = FileClassifier.classify(path, 2_400);
        assertThat(c.ignored()).isFalse();
        assertThat(c.ignoreReason()).isNull();
        assertThat(c.language()).isEqualTo(language == null || language.isBlank() ? null : language);
    }

    @ParameterizedTest
    @CsvSource({ "src/huge.json, 1048577", "data/seed.sql, 5000000" })
    void filesOverOneMegabyteAreIgnored(String path, long size) {
        assertThat(FileClassifier.classify(path, size).ignoreReason()).isEqualTo("Larger than 1 MB");
    }

    @ParameterizedTest
    @CsvSource({ "src/api/orders.test.ts, orders.test.ts, ts", "Dockerfile, Dockerfile, ", ".gitignore, .gitignore, " })
    void splitsFileNameAndExtension(String path, String fileName, String extension) {
        FileClassifier.Classification c = FileClassifier.classify(path, 1);
        assertThat(c.fileName()).isEqualTo(fileName);
        assertThat(c.extension()).isEqualTo(extension == null || extension.isBlank() ? null : extension);
    }
}
