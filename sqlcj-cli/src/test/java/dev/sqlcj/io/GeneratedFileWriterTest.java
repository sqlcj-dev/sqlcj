package dev.sqlcj.io;

import dev.sqlcj.generator.GeneratedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        Path outputFile = outputDirectory.resolve("GetUser.java");

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

        Path outputFile = outputDirectory.resolve("nested").resolve("GetUser.java");

        assertTrue(Files.exists(outputFile));
        assertEquals(
            "public final class GetUser {}",
            Files.readString(outputFile)
        );
    }

    @Test
    void shouldReportTheDirectoryItCannotCreate() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Files.writeString(outputDirectory, "not a directory");

        GeneratedFile file = new GeneratedFile(
            Path.of("nested", "GetUser.java"),
            "public final class GetUser {}"
        );

        IOException failure = assertThrows(
            IOException.class,
            () -> writer.write(file, outputDirectory)
        );

        assertEquals(
            "Cannot create output directory: "
                + outputDirectory.resolve(file.path()).getParent(),
            failure.getMessage()
        );
    }

    @Test
    void shouldReportTheFileItCannotWrite() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = new GeneratedFile(
            Path.of("GetUser.java"),
            "public final class GetUser {}"
        );

        Files.createDirectories(outputDirectory.resolve(file.path()));

        IOException failure = assertThrows(
            IOException.class,
            () -> writer.write(file, outputDirectory)
        );

        assertEquals(
            "Cannot write generated file: " + outputDirectory.resolve(file.path()),
            failure.getMessage()
        );
    }
}
