package dev.sqlcj.io;

import dev.sqlcj.generator.GeneratedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedFileWriterTest {

    @TempDir
    Path tempDir;

    private final GeneratedFileWriter writer = new GeneratedFileWriter();

    @Test
    void shouldWriteGeneratedFileToOutputDirectory() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = new GeneratedFile(
                Path.of("GetUser.java"),
                "public final class GetUser {}"
        );

        writer.write(file, outputDirectory);

        Path outputFile =
                outputDirectory.resolve("GetUser.java");

        assertTrue(Files.exists(outputFile));
        assertEquals(
                "public final class GetUser {}",
                Files.readString(outputFile)
        );
    }

    @Test
    void shouldCreateParentDirectories() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = new GeneratedFile(
                Path.of("nested", "GetUser.java"),
                "public final class GetUser {}"
        );

        writer.write(file, outputDirectory);

        Path outputFile =
                outputDirectory.resolve("nested").resolve("GetUser.java");

        assertTrue(Files.exists(outputFile));
        assertEquals(
                "public final class GetUser {}",
                Files.readString(outputFile)
        );
    }
}
