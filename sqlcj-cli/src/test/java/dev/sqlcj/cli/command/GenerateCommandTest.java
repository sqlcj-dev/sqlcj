package dev.sqlcj.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code generate} command from its fixed {@code sqlcj.yaml}
 * entry point in an isolated working directory.
 */
class GenerateCommandTest {

    private static final String SCHEMA = """
        CREATE TABLE users
        (
            id   BIGINT NOT NULL,
            name VARCHAR(255)
        );
        """;

    private static final String QUERIES = """
        -- name: GetUser :one
        SELECT id, name
        FROM users
        WHERE id = $1;
        """;

    @TempDir
    Path workingDirectory;

    @Test
    void shouldGenerateFromConfigurationInWorkingDirectory() throws Exception {
        Files.writeString(workingDirectory.resolve("schema.sql"), SCHEMA);
        Files.writeString(workingDirectory.resolve("queries.sql"), QUERIES);
        Files.writeString(
            workingDirectory.resolve("sqlcj.yaml"),
            """
                version: "1"
                sql:
                  - schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
        );

        Result result = runGenerate();

        assertEquals(0, result.exitCode(), result.error());

        Path generated = workingDirectory.resolve("generated/dev/example/generated/GetUser.java");

        assertTrue(Files.exists(generated), result.error());

        assertTrue(
            Files.readString(generated)
                .startsWith("package dev.example.generated;")
        );
    }

    @Test
    void shouldFailWithConciseDiagnosticForMalformedConfiguration() throws Exception {
        Files.writeString(
            workingDirectory.resolve("sqlcj.yaml"),
            """
                version: "1"
                sql:
                  - schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  output: generated
                """
        );

        Result result = runGenerate();

        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("sqlcj: Invalid configuration"));
        assertTrue(result.error().contains("unknown field 'output'"));
        assertFalse(result.error().contains("\tat "));
    }

    @Test
    void shouldFailWithConciseDiagnosticForUnreadableSource() throws Exception {
        Files.writeString(workingDirectory.resolve("schema.sql"), SCHEMA);
        Files.writeString(
            workingDirectory.resolve("sqlcj.yaml"),
            """
                version: "1"
                sql:
                  - schema: schema.sql
                    queries: missing.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
        );

        Result result = runGenerate();

        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("sqlcj: Cannot read queries source"));
        assertTrue(
            result.error().contains(
                workingDirectory.resolve("missing.sql").toString()
            )
        );
        assertFalse(result.error().contains("\tat "));
        assertFalse(Files.exists(workingDirectory.resolve("generated")));
    }

    @Test
    void shouldFailWithConciseDiagnosticForInvalidQuery() throws Exception {
        Files.writeString(workingDirectory.resolve("schema.sql"), SCHEMA);
        Files.writeString(
            workingDirectory.resolve("queries.sql"),
            """
                -- name: ListUsers :many
                SELECT id
                FROM users;

                -- name: GetUser :one
                SELECT id, name
                FROM users
                WHERE id = $1
                  AND name = $3;
                """
        );
        Files.writeString(
            workingDirectory.resolve("sqlcj.yaml"),
            """
                version: "1"
                sql:
                  - schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
        );

        Result result = runGenerate();

        assertEquals(1, result.exitCode(), result.error());

        assertTrue(
            result.error().contains(
                "sqlcj: Invalid query 'GetUser' in %s at line 5: "
                    .formatted(workingDirectory.resolve("queries.sql"))
                    + "Placeholder indexes must start at $1 without gaps, but were [1, 3]"
            ),
            result.error()
        );

        assertFalse(result.error().contains("\tat "));
        assertFalse(Files.exists(workingDirectory.resolve("generated")));
    }

    private Result runGenerate() throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        Process process = new ProcessBuilder(
            java.toString(),
            "-classpath",
            System.getProperty("java.class.path"),
            "dev.sqlcj.Main",
            "generate"
        )
            .directory(workingDirectory.toFile())
            .start();

        String output = new String(process.getInputStream().readAllBytes());
        String error = new String(process.getErrorStream().readAllBytes());

        assertTrue(
            process.waitFor(60, TimeUnit.SECONDS),
            "sqlcj generate did not terminate"
        );

        return new Result(process.exitValue(), output, error);
    }

    private record Result(
        int exitCode,
        String output,
        String error
    ) {
    }
}
