package com.engineeringlens.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.engineeringlens.analysis.common.RepoPaths;

/** Path conventions, including the false positives found on real repositories during verification. */
class RepoPathsTest {

    @ParameterizedTest
    @ValueSource(strings = { ".env.example", ".env.sample", ".env.template", "app/.env.example-e2e", ".env.local.example",
            ".env.dist", ".env-example" })
    void envTemplatesAreNotSecrets(String path) {
        assertThat(RepoPaths.isEnvTemplate(path)).isTrue();
        assertThat(RepoPaths.isSecretLike(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { ".env", "frontend/.env", ".env.local", ".env.production", "certs/server.pem", "keys/id_rsa",
            "android/release.keystore", "service-account-prod.json" })
    void secretLikeFiles(String path) {
        assertThat(RepoPaths.isSecretLike(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "frontend/src/main.tsx", "src/index.ts", "server.js", "backend/app/main.py", "manage.py",
            "cmd/api/main.go", "src/main/java/com/demo/DemoApplication.java" })
    void realEntrypoints(String path) {
        assertThat(RepoPaths.isEntrypoint(path, language(path))).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "frontend/src/components/Sidebar/Main.tsx", "apps/web/.storybook/main.ts",
            "src/testing/mocks/server.ts", "src/components/errors/main.tsx", "src/features/auth/index.ts",
            "src/main/java/com/demo/model/LoanApplication.java", "tests/test_main.py" })
    void notEntrypoints(String path) {
        assertThat(RepoPaths.isEntrypoint(path, language(path))).as(path).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "tests/conftest.py", "tests/__init__.py", "tests/fixtures/data.json" })
    void testInfrastructureIsNotATest(String path) {
        assertThat(RepoPaths.isTestFile(path, language(path))).isFalse();
    }

    private static String language(String path) {
        return Fixtures.file(path, 1).language();
    }
}
