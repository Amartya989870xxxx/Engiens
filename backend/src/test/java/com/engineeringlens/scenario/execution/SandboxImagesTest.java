package com.engineeringlens.scenario.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The sandbox only ever runs digest-pinned images, and the pull script (dev, CI, production) pulls exactly those. */
class SandboxImagesTest {

    @Test
    void everySandboxImageIsPinnedByDigestAndPulledByTheDeployScript() throws Exception {
        String script = Files.readString(Path.of("../deploy/sandbox-images.sh"));
        assertThat(LanguageRuntime.images()).isNotEmpty().allSatisfy(image -> {
            assertThat(image).matches("[a-z0-9.\\-/]+:[\\w.\\-]+@sha256:[0-9a-f]{64}");
            assertThat(script).contains("\"" + image + "\"");
        });
        assertThat(script.split("@sha256:").length - 1).isEqualTo(LanguageRuntime.images().size());
    }
}
