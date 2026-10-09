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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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

        assertTrue(schemaOf(sources.get(0)).contains("CREATE TABLE users"));
        assertTrue(schemaOf(sources.get(1)).contains("CREATE TABLE orders"));

        assertEquals(
            List.of("GetUser", "ListUsers"),
            names(sources.get(0))
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

        assertEquals(List.of("GetUser"), names(sources.get(0)));
        assertEquals(List.of("GetUser"), names(sources.get(1)));

        assertEquals("Users", sources.get(0).name());
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

        Source source = sources.get(0);

        assertEquals("Users", source.name());
        assertEquals(List.of(schema), schemaPaths(source));
        assertEquals(queries, source.queriesPath());
        assertEquals(1, source.queries().get(0).line());
        assertEquals(6, source.queries().get(1).line());
    }

    @Test
    void shouldLoadListedFilesAndDirectoryMigrationsInOrder() throws IOException {
        Path prelude = write("prelude.sql", table("accounts"));

        Path migrations = directory("migrations");

        writeIn(migrations, "V10__ten.sql", table("ten"));
        writeIn(migrations, "V2__two.sql", table("two"));
        writeIn(migrations, "V1_1__one_one.sql", table("one_one"));
        writeIn(migrations, "V1__one.sql", table("one"));
        writeIn(migrations, "baseline.sql", table("baseline"));
        writeIn(migrations, "a_extra.sql", table("a_extra"));
        writeIn(migrations, "U1__undo.sql", table("undo"));
        writeIn(migrations, "V3__upper.SQL", table("upper"));
        writeIn(migrations, "notes.txt", "not sql");

        Path nested = directory("migrations/nested.sql");

        writeIn(nested, "V4__nested.sql", table("nested"));

        Path queries = write("queries.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;
            """);

        List<Source> sources = sourceLoader.load(
            config(
                new SqlConfig(
                    "Users",
                    List.of(prelude.toString(), migrations.toString()),
                    queries.toString()
                )
            )
        );

        Source source = sources.get(0);

        assertEquals(
            List.of(
                prelude,
                migrations.resolve("V1__one.sql"),
                migrations.resolve("V1_1__one_one.sql"),
                migrations.resolve("V2__two.sql"),
                migrations.resolve("V10__ten.sql"),
                migrations.resolve("a_extra.sql"),
                migrations.resolve("baseline.sql")
            ),
            schemaPaths(source)
        );

        assertEquals(table("accounts"), schemaOf(source));
        assertEquals(table("one"), source.schemaFiles().get(1).sql());
    }

    @Test
    void shouldRejectDuplicateMigrationVersionsInDirectory() throws IOException {
        Path migrations = directory("migrations");

        writeIn(migrations, "V1__a.sql", table("a"));
        writeIn(migrations, "V1_0__b.sql", table("b"));

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
                        migrations.toString(),
                        queries.toString()
                    )
                )
            )
        );

        assertEquals(
            "Invalid schema source %s: duplicate migration version in V1_0__b.sql and V1__a.sql"
                .formatted(migrations),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectSchemaDirectoryWithoutSqlFiles() throws IOException {
        Path migrations = directory("migrations");

        writeIn(migrations, "U1__undo.sql", table("undo"));
        writeIn(migrations, "notes.txt", "not sql");

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
                        migrations.toString(),
                        queries.toString()
                    )
                )
            )
        );

        assertEquals(
            "Invalid schema source %s: directory contains no .sql files"
                .formatted(migrations),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnlistableSchemaDirectory() throws IOException {
        Path migrations = directory("migrations");

        writeIn(migrations, "V1__one.sql", table("one"));

        Path queries = write("queries.sql", """
            -- name: GetUser :one
            SELECT id
            FROM users
            WHERE id = $1;
            """);

        Files.setPosixFilePermissions(
            migrations,
            PosixFilePermissions.fromString("-wx------")
        );

        try {
            assumeFalse(
                Files.isReadable(migrations),
                "requires a filesystem that enforces directory permissions"
            );

            CompilationException exception = assertThrows(
                CompilationException.class,
                () -> sourceLoader.load(
                    config(
                        new SqlConfig(
                            "Users",
                            migrations.toString(),
                            queries.toString()
                        )
                    )
                )
            );

            assertEquals(
                "Cannot read schema source: %s: Permission denied".formatted(migrations),
                exception.getMessage()
            );
        } finally {
            Files.setPosixFilePermissions(
                migrations,
                PosixFilePermissions.fromString("rwx------")
            );
        }
    }

    private String schemaOf(Source source) {
        return source.schemaFiles().get(0).sql();
    }

    private List<Path> schemaPaths(Source source) {
        return source.schemaFiles().stream().map(SchemaFile::path).toList();
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

    private String table(String name) {
        return """
            CREATE TABLE %s
            (
                id BIGINT NOT NULL
            );
            """.formatted(name);
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private void writeIn(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);

        Files.writeString(file, content);
    }

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);

        Files.writeString(file, content);

        return file;
    }
}
