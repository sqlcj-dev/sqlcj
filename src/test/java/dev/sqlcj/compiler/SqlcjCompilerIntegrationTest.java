package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlcjCompilerIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void cleanGeneratedDirectory() throws IOException {
        Path generated = Path.of("generated");

        if (!Files.exists(generated)) {
            return;
        }

        try (var paths = Files.walk(generated)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    @Test
    void shouldGenerateJavaFiles() throws IOException {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");

        Files.writeString(
                schemaFile,
                """
                        CREATE TABLE users (
                            id BIGINT NOT NULL,
                            name VARCHAR(255),
                            active BOOLEAN
                        );
                        """
        );

        Files.writeString(
                queriesFile,
                """
                -- name: GetUser :one
                SELECT *
                FROM users
                WHERE id = $1;

                -- name: ListUsers :many
                SELECT *
                FROM users;
                """
        );

        Config config = new Config(
                List.of(
                        new SqlConfig(
                                schemaFile.toString(),
                                queriesFile.toString()
                        )
                ),
                new JavaConfig(
                        tempDir.resolve("generated").toString(),
                        "generated"
                )
        );

        SqlcjCompiler compiler = new SqlcjCompiler();

        compiler.compile(config);

        Path getUserFile = Path.of("generated", "GetUser.java");
        Path listUsersFile = Path.of("generated", "ListUsers.java");

        assertTrue(Files.exists(getUserFile));
        assertTrue(Files.exists(listUsersFile));

        String getUser = Files.readString(getUserFile);
        String listUsers = Files.readString(listUsersFile);

        assertTrue(getUser.contains("public final class GetUser"));
        assertTrue(getUser.contains("Long param1"));
        assertTrue(getUser.contains("public Result getUser(Long param1)"));
        assertTrue(getUser.contains("Long id"));
        assertTrue(getUser.contains("String name"));
        assertTrue(getUser.contains("Boolean active"));

        assertTrue(listUsers.contains("public final class ListUsers"));
        assertTrue(listUsers.contains("import java.util.List;"));
        assertTrue(listUsers.contains("public List<Result> listUsers()"));
    }
}
