package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.parser.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSourceLoaderTest {

    private final SourceLoader sourceLoader = new DefaultSourceLoader();

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadEntriesInDeclaredOrder() throws IOException {
        Path userSchema = write("users-schema.sql", """
            CREATE TABLE users
            (
                id BIGINT NOT NULL
            );
            """);

        Path userQueries = write("users-queries.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;

            -- name: ListUsers :many
            SELECT id
            FROM users;
            """);

        Path orderSchema = write("orders-schema.sql", """
            CREATE TABLE orders
            (
                id BIGINT NOT NULL
            );
            """);

        Path orderQueries = write("orders-queries.sql", """
            -- name: GetOrder :one
            SELECT id
            FROM orders
            WHERE id = $1;
            """);

        List<Source> sources = sourceLoader.load(
            config(
                new SqlConfig("Users", userSchema.toString(), userQueries.toString()),
                new SqlConfig("Orders", orderSchema.toString(), orderQueries.toString())
            )
        );

        assertEquals(2, sources.size());

        assertTrue(sources.getFirst().schema().contains("CREATE TABLE users"));
        assertTrue(sources.get(1).schema().contains("CREATE TABLE orders"));

        assertEquals(
            List.of("GetUser", "ListUsers"),
            names(sources.getFirst())
        );

        assertEquals(
            List.of("GetOrder"),
            names(sources.get(1))
        );
    }

    @Test
    void shouldLoadTheSameQueryNameInTwoEntries() throws IOException {
        Path schema = write("schema.sql", """
            CREATE TABLE users
            (
                id BIGINT NOT NULL
            );
            """);

        Path firstQueries = write("first.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;
            """);

        Path secondQueries = write("second.sql", """
            -- name: GetUser :many
            SELECT id
            FROM users;
            """);

        List<Source> sources = sourceLoader.load(
            config(
                new SqlConfig("Users", schema.toString(), firstQueries.toString()),
                new SqlConfig("Orders", schema.toString(), secondQueries.toString())
            )
        );

        assertEquals(List.of("GetUser"), names(sources.getFirst()));
        assertEquals(List.of("GetUser"), names(sources.get(1)));

        assertEquals("Users", sources.getFirst().name());
        assertEquals("Orders", sources.get(1).name());
    }

    @Test
    void shouldRejectUnreadableQueriesSource() throws IOException {
        Path schema = write("schema.sql", """
            CREATE TABLE users
            (
                id BIGINT NOT NULL
            );
            """);

        Path missingQueries = tempDir.resolve("missing.sql");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> sourceLoader.load(
                config(
                    new SqlConfig(
                        "Users",
                        schema.toString(),
                        missingQueries.toString()
                    )
                )
            )
        );

        assertEquals(
            "Cannot read queries source: %s: No such file or directory"
                .formatted(missingQueries),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnreadableSchemaSource() throws IOException {
        Path missingSchema = tempDir.resolve("missing.sql");

        Path queries = write("queries.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;
            """);

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> sourceLoader.load(
                config(
                    new SqlConfig(
                        "Users",
                        missingSchema.toString(),
                        queries.toString()
                    )
                )
            )
        );

        assertEquals(
            "Cannot read schema source: %s: No such file or directory"
                .formatted(missingSchema),
            exception.getMessage()
        );
    }

    @Test
    void shouldReportInvalidQuerySourceWithItsPath() throws IOException {
        Path schema = write("schema.sql", """
            CREATE TABLE users
            (
                id BIGINT NOT NULL
            );
            """);

        Path queries = write("queries.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;

            -- name: GetUser :one
            SELECT id
            FROM users;
            """);

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> sourceLoader.load(
                config(
                    new SqlConfig(
                        "Users",
                        schema.toString(),
                        queries.toString()
                    )
                )
            )
        );

        assertTrue(
            exception.getMessage().startsWith(
                "Invalid query source " + queries + ":"
            ),
            exception.getMessage()
        );
    }

    @Test
    void shouldCarrySourcePathsAndQueryLines() throws IOException {
        Path schema = write("schema.sql", """
            CREATE TABLE users
            (
                id BIGINT NOT NULL
            );
            """);

        Path queries = write("queries.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;

            -- name: ListUsers :many
            SELECT id
            FROM users;
            """);

        List<Source> sources = sourceLoader.load(
            config(
                new SqlConfig(
                    "Users",
                    schema.toString(),
                    queries.toString()
                )
            )
        );

        Source source = sources.getFirst();

        assertEquals("Users", source.name());
        assertEquals(schema, source.schemaPath());
        assertEquals(queries, source.queriesPath());
        assertEquals(1, source.queries().getFirst().line());
        assertEquals(6, source.queries().get(1).line());
    }

    private List<String> names(Source source) {
        return source.queries().stream().map(Query::name).toList();
    }

    private Config config(SqlConfig... entries) {
        return new Config(
            List.of(entries),
            new JavaConfig(
                tempDir.resolve("generated").toString(),
                "dev.example.generated"
            )
        );
    }

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);

        Files.writeString(file, content);

        return file;
    }
}
