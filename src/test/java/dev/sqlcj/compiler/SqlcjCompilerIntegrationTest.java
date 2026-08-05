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
        Path schema = tempDir.resolve("schema.sql");
        Path queries = tempDir.resolve("queries.sql");

        Files.writeString(schema, "");
        Files.writeString(
                queries,
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
                                schema.toString(),
                                queries.toString()
                        )
                ),
                new JavaConfig(
                        tempDir.toString(),
                        "generated"
                )
        );

        SqlcjCompiler sqlcjCompiler = new SqlcjCompiler();
        sqlcjCompiler.compile(config);

        Path getUser = Path.of("generated", "GetUser.java");
        assertTrue(Files.exists(getUser));

        Path listUsers = Path.of("generated", "ListUsers.java");
        assertTrue(Files.exists(listUsers));

        String source = Files.readString(getUser);
        assertTrue(source.contains("class GetUser"));
        assertTrue(source.contains("Object getUser"));
        assertTrue(source.contains("Object param1"));
    }
}
