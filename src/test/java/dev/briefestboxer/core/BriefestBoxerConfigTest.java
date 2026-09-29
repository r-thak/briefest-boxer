package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BriefestBoxerConfigTest {
    @TempDir
    Path gameDirectory;

    @Test
    void migratesOldDefaultTrajectoryHorizonToLongerPrediction() throws IOException {
        Path configDirectory = Files.createDirectories(gameDirectory.resolve("config"));
        Files.write(configDirectory.resolve("briefest_boxer.properties"),
                "trajectorySteps=48".getBytes(StandardCharsets.UTF_8));

        BriefestBoxerConfig.load(gameDirectory);

        assertEquals(256, BriefestBoxerConfig.trajectorySteps);
    }

    @Test
    void preservesAnExplicitLongerTrajectoryHorizon() throws IOException {
        Path configDirectory = Files.createDirectories(gameDirectory.resolve("config"));
        Files.write(configDirectory.resolve("briefest_boxer.properties"),
                "trajectorySteps=192".getBytes(StandardCharsets.UTF_8));

        BriefestBoxerConfig.load(gameDirectory);

        assertEquals(192, BriefestBoxerConfig.trajectorySteps);
    }
}
