package dev.sqlcj.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code generate} command from its default {@code sqlcj.yaml}
 * entry point and from an explicit {@code --config} path, in an isolated
 * working directory.
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
                  - name: Users
                    schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
        );

        Result result = runGenerate();

        assertEquals(0, result.exitCode(), result.error());

        Path generated = workingDirectory.resolve("generated/dev/example/generated/UsersRepository.java");

        assertTrue(Files.exists(generated), result.error());

        String source = Files.readString(generated);

        assertTrue(source.startsWith("package dev.example.generated;"));
        assertTrue(source.contains("public final class UsersRepository {"));
        assertTrue(source.contains("public GetUserResult getUser(Long id)"));
    }

    @Test
    void shouldGenerateFromExplicitConfigurationPathInAnotherDirectory() throws Exception {
        Path project = Files.createDirectory(workingDirectory.resolve("project"));
        Path elsewhere = Files.createDirectory(workingDirectory.resolve("elsewhere"));

        Files.writeString(project.resolve("schema.sql"), SCHEMA);
        Files.writeString(project.resolve("queries.sql"), QUERIES);
        Files.writeString(
            project.resolve("sqlcj-users.yaml"),
            """
                version: "1"
                sql:
                  - name: Users
                    schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
        );

        Result result = runGenerateIn(elsewhere, "--config", "../project/sqlcj-users.yaml");

        assertEquals(0, result.exitCode(), result.error());

        Path generated = project.resolve("generated/dev/example/generated/UsersRepository.java");

        assertTrue(Files.exists(generated), result.error());
        assertTrue(
            Files.readString(generated).contains("public GetUserResult getUser(Long id)")
        );

        try (Stream<Path> entries = Files.list(elsewhere)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void shouldFailWithoutFallbackWhenExplicitConfigurationIsMissing() throws Exception {
        Files.writeString(workingDirectory.resolve("schema.sql"), SCHEMA);
        Files.writeString(workingDirectory.resolve("queries.sql"), QUERIES);
        Files.writeString(
            workingDirectory.resolve("sqlcj.yaml"),
            """
                version: "1"
                sql:
                  - name: Users
                    schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
        );

        Result result = runGenerate("--config", "missing.yaml");

        assertEquals(1, result.exitCode(), result.error());
        assertTrue(
            result.error().contains(
                "sqlcj: Cannot read configuration file: "
                    + workingDirectory.resolve("missing.yaml")
            ),
            result.error()
        );
        assertFalse(result.error().contains("\tat "));
        assertFalse(Files.exists(workingDirectory.resolve("generated")));
    }

    @Test
    void shouldFailWithConciseDiagnosticForMalformedConfiguration() throws Exception {
        Files.writeString(
            workingDirectory.resolve("sqlcj.yaml"),
            """
                version: "1"
                sql:
                  - name: Users
                    schema: schema.sql
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
                  - name: Users
                    schema: schema.sql
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
                  - name: Users
                    schema: schema.sql
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

    private Result runGenerate(String... arguments) throws IOException, InterruptedException {
        return runGenerateIn(workingDirectory, arguments);
    }

    private Result runGenerateIn(Path directory, String... arguments)
        throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> command = new ArrayList<>(
            List.of(
                java.toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                "dev.sqlcj.Main",
                "generate"
            )
        );

        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command)
            .directory(directory.toFile())
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
