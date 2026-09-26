package dev.sqlcj.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedOutputManifestTest {

    @TempDir
    Path tempDir;

    private final GeneratedOutputManifest manifest = new GeneratedOutputManifest();

    @Test
    void shouldReadNoEntryWithoutAManifest() throws IOException {
        assertEquals(List.of(), manifest.read(tempDir.resolve("generated")));
    }

    @Test
    void shouldReadTheEntriesOfAPreviousManifest() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Files.createDirectories(outputDirectory);

        Files.writeString(
            outputDirectory.resolve("sqlcj-manifest.txt"),
            """
                dev/example/generated/OrdersRepository.java
                dev/example/generated/UsersRepository.java
                """
        );

        assertEquals(
            List.of(
                "dev/example/generated/OrdersRepository.java",
                "dev/example/generated/UsersRepository.java"
            ),
            manifest.read(outputDirectory)
        );
    }

    @Test
    void shouldWriteSortedEntriesTerminatedByNewlines() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Files.createDirectories(outputDirectory);

        manifest.write(
            outputDirectory,
            List.of(
                Path.of("dev", "example", "generated", "UsersRepository.java"),
                Path.of("dev", "example", "generated", "OrdersRepository.java")
            )
        );

        assertEquals(
            "dev/example/generated/OrdersRepository.java\n"
                + "dev/example/generated/UsersRepository.java\n",
            Files.readString(outputDirectory.resolve("sqlcj-manifest.txt"))
        );
    }

    @Test
    void shouldReportTheManifestItCannotRead() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");
        Path manifestFile = outputDirectory.resolve("sqlcj-manifest.txt");

        Files.createDirectories(manifestFile);

        IOException failure = assertThrows(
            IOException.class,
            () -> manifest.read(outputDirectory)
        );

        assertEquals(
            "Cannot read output manifest: %s: Is a directory".formatted(manifestFile),
            failure.getMessage()
        );
    }

    @Test
    void shouldReportTheManifestItCannotWrite() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");
        Path manifestFile = outputDirectory.resolve("sqlcj-manifest.txt");

        Files.createDirectories(manifestFile);

        IOException failure = assertThrows(
            IOException.class,
            () -> manifest.write(
                outputDirectory,
                List.of(Path.of("generated", "UsersRepository.java"))
            )
        );

        assertEquals(
            "Cannot write output manifest: %s: Is a directory".formatted(manifestFile),
            failure.getMessage()
        );
    }

    @Test
    void shouldDeleteOnlyAListedFileThisRunDidNotProduce() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Path produced = write(outputDirectory, "generated/UsersRepository.java");
        Path stale = write(outputDirectory, "generated/OrdersRepository.java");

        manifest.deleteStale(
            outputDirectory,
            List.of(
                "generated/UsersRepository.java",
                "generated/OrdersRepository.java"
            ),
            List.of(Path.of("generated", "UsersRepository.java"))
        );

        assertTrue(Files.exists(produced));
        assertFalse(Files.exists(stale));
    }

    @Test
    void shouldKeepAnUnlistedFile() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        write(outputDirectory, "generated/UsersRepository.java");

        Path unlisted = write(outputDirectory, "generated/Helper.java");

        manifest.deleteStale(
            outputDirectory,
            List.of("generated/UsersRepository.java"),
            List.of(Path.of("generated", "UsersRepository.java"))
        );

        assertTrue(Files.exists(unlisted));
    }

    /**
     * An entry that escapes the output directory, is absolute, is not
     * normalized, is blank, names a directory, or names the same file this run
     * produced is never deleted.
     */
    @Test
    void shouldKeepAListedEntryItMustNotDelete() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Path produced = write(outputDirectory, "generated/UsersRepository.java");

        Path outside = tempDir.resolve("outside.java");

        Files.writeString(outside, "// outside\n");

        Path absolute = write(outputDirectory, "generated/AbsoluteRepository.java");
        Path notNormalized = write(outputDirectory, "generated/StaleRepository.java");
        Path directory = outputDirectory.resolve("generated/nested");

        Files.createDirectories(directory);

        Path hardLink = outputDirectory.resolve("generated/LinkedRepository.java");

        Files.createLink(hardLink, produced);

        manifest.deleteStale(
            outputDirectory,
            List.of(
                "../outside.java",
                absolute.toString(),
                "generated/./StaleRepository.java",
                "",
                "generated/nested",
                "generated/LinkedRepository.java"
            ),
            List.of(Path.of("generated", "UsersRepository.java"))
        );

        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(absolute));
        assertTrue(Files.exists(notNormalized));
        assertTrue(Files.isDirectory(directory));
        assertTrue(Files.exists(hardLink));
        assertTrue(Files.exists(produced));
    }

    private Path write(Path outputDirectory, String entry) throws IOException {
        Path file = outputDirectory.resolve(entry);

        Files.createDirectories(file.getParent());

        Files.writeString(file, "// " + entry + "\n");

        return file;
    }
}
