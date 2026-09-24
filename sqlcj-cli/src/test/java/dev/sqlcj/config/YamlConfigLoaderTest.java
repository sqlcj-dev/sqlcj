package dev.sqlcj.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigLoaderTest {

    private final ConfigLoader configLoader = new YamlConfigLoader();

    @TempDir
    Path tempDir;

    @Test
    void shouldMapVersionOneFields() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        Config config = configLoader.load(configFile);

        assertEquals("1", config.version());
        assertEquals(1, config.sql().size());
        assertEquals("Users", config.sql().getFirst().name());
        assertEquals(
            tempDir.resolve("schema.sql").toString(),
            config.sql().getFirst().schema()
        );
        assertEquals(
            tempDir.resolve("queries.sql").toString(),
            config.sql().getFirst().queries()
        );
        assertEquals(
            "dev.example.generated",
            config.java().packageName()
        );
        assertEquals(
            tempDir.resolve("generated").toString(),
            config.java().out()
        );
    }

    @Test
    void shouldPreserveSqlEntryOrder() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: users.sql
                queries: user-queries.sql
              - name: Orders
                schema: orders.sql
                queries: order-queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        Config config = configLoader.load(configFile);

        assertEquals(
            List.of(
                tempDir.resolve("users.sql").toString(),
                tempDir.resolve("orders.sql").toString()
            ),
            config.sql().stream().map(SqlConfig::schema).toList()
        );

        assertEquals(
            List.of(
                tempDir.resolve("user-queries.sql").toString(),
                tempDir.resolve("order-queries.sql").toString()
            ),
            config.sql().stream().map(SqlConfig::queries).toList()
        );
    }

    @Test
    void shouldResolveRelativePathsAgainstConfigurationDirectory() throws IOException {
        Path configDirectory = Files.createDirectories(
            tempDir.resolve("project").resolve("config")
        );

        Path configFile = configDirectory.resolve("sqlcj.yaml");

        Files.writeString(configFile, """
            version: "1"
            sql:
              - name: Users
                schema: ../sql/schema.sql
                queries: ./queries.sql
            java:
              package: dev.example.generated
              out: ../target/generated
            """);

        Config config = configLoader.load(
            Path.of(configFile.toString())
        );

        assertEquals(
            tempDir.resolve("project/sql/schema.sql").toString(),
            config.sql().getFirst().schema()
        );

        assertEquals(
            configDirectory.resolve("queries.sql").toString(),
            config.sql().getFirst().queries()
        );

        assertEquals(
            tempDir.resolve("project/target/generated").toString(),
            config.java().out()
        );
    }

    @Test
    void shouldKeepAbsolutePathsAbsolute() throws IOException {
        Path schemaFile = tempDir.resolve("absolute").resolve("schema.sql");
        Path queriesFile = tempDir.resolve("absolute").resolve("queries.sql");
        Path outputDirectory = tempDir.resolve("absolute").resolve("out");

        Path configFile = write(
            """
                version: "1"
                sql:
                  - name: Users
                    schema: %s
                    queries: %s
                java:
                  package: dev.example.generated
                  out: %s
                """
                .formatted(schemaFile, queriesFile, outputDirectory)
        );

        Config config = configLoader.load(configFile);

        assertEquals(
            schemaFile.toString(),
            config.sql().getFirst().schema()
        );

        assertEquals(
            queriesFile.toString(),
            config.sql().getFirst().queries()
        );

        assertEquals(
            outputDirectory.toString(),
            config.java().out()
        );
    }

    @Test
    void shouldRejectMissingConfigurationFile() {
        Path configFile = tempDir.resolve("sqlcj.yaml");

        ConfigurationException exception = assertThrows(
            ConfigurationException.class,
            () -> configLoader.load(configFile)
        );

        assertTrue(
            exception.getMessage().contains(
                "Cannot read configuration file: " + configFile
            ),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectMalformedYaml() throws IOException {
        Path configFile = write("""
            version: "1"
              sql: [
            """);

        ConfigurationException exception = assertThrows(
            ConfigurationException.class,
            () -> configLoader.load(configFile)
        );

        assertTrue(
            exception.getMessage().contains(
                "Malformed configuration file: " + configFile
            ),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnknownField() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
              indent: 2
            """);

        assertInvalid(configFile, "unknown field 'indent'");
    }

    @Test
    void shouldRejectWrongTypedField() throws IOException {
        Path configFile = write("""
            version: "1"
            sql: schema.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(
            configFile,
            "invalid 'sql' value of type string, expected a list"
        );
    }

    @Test
    void shouldRejectWrongTypedNestedField() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: 42
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(
            configFile,
            "invalid 'sql[0].schema' value '42' of type number, "
                + "expected a string"
        );
    }

    @Test
    void shouldRejectWrongTypedJavaOut() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out:
                - generated
            """);

        assertInvalid(
            configFile,
            "invalid 'java.out' value of type list, expected a string"
        );
    }

    @Test
    void shouldRejectNumericVersion() throws IOException {
        Path configFile = write("""
            version: 1
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(
            configFile,
            "invalid 'version' value '1' of type number, "
                + "expected a string"
        );
    }

    @Test
    void shouldRejectMissingVersion() throws IOException {
        Path configFile = write("""
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'version' is required");
    }

    @Test
    void shouldRejectUnsupportedVersion() throws IOException {
        Path configFile = write("""
            version: "2"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(
            configFile,
            "unsupported 'version' value '2', expected '1'"
        );
    }

    @Test
    void shouldRejectMissingSqlSection() throws IOException {
        Path configFile = write("""
            version: "1"
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'sql' is required");
    }

    @Test
    void shouldRejectEmptySqlList() throws IOException {
        Path configFile = write("""
            version: "1"
            sql: []
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'sql' must contain at least one entry");
    }

    @Test
    void shouldRejectNullSqlEntry() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
              -
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'sql[1]' must not be null");
    }

    @Test
    void shouldRejectBlankQueriesValue() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: "  "
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'sql[0].queries' must not be blank");
    }

    @Test
    void shouldRejectMissingSqlEntryName() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'sql[0].name' is required");
    }

    @Test
    void shouldRejectBlankSqlEntryName() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: "  "
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        assertInvalid(configFile, "'sql[0].name' must not be blank");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "Author Repository",
            "1Author",
            "Author.Repository",
            "class",
            "true",
            "null",
            "_",
            "var",
            "record"
        }
    )
    void shouldRejectSqlEntryNameThatIsNotAJavaIdentifier(String name) throws IOException {
        Path configFile = write(
            """
                version: "1"
                sql:
                  - name: "%s"
                    schema: schema.sql
                    queries: queries.sql
                java:
                  package: dev.example.generated
                  out: generated
                """
                .formatted(name)
        );

        assertInvalid(
            configFile,
            "'sql[0].name' value '%s' is not a valid Java identifier for a generated repository name"
                .formatted(name)
        );
    }

    @Test
    void shouldPreserveSqlEntryNameWhileResolvingPaths() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Author
                schema: sql/schema.sql
                queries: sql/queries.sql
            java:
              package: dev.example.generated
              out: generated
            """);

        Config config = configLoader.load(configFile);

        assertEquals("Author", config.sql().getFirst().name());

        assertEquals(
            tempDir.resolve("sql/schema.sql").toString(),
            config.sql().getFirst().schema()
        );
    }

    @Test
    void shouldRejectMissingJavaSection() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            """);

        assertInvalid(configFile, "'java' is required");
    }

    @Test
    void shouldRejectMissingJavaOut() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.example.generated
            """);

        assertInvalid(configFile, "'java.out' is required");
    }

    @Test
    void shouldRejectInvalidJavaPackage() throws IOException {
        Path configFile = write("""
            version: "1"
            sql:
              - name: Users
                schema: schema.sql
                queries: queries.sql
            java:
              package: dev.class.generated
              out: generated
            """);

        assertInvalid(
            configFile,
            "'java.package' value 'dev.class.generated' "
                + "is not a valid Java package name"
        );
    }

    private void assertInvalid(Path configFile, String detail) {
        ConfigurationException exception = assertThrows(
            ConfigurationException.class,
            () -> configLoader.load(configFile)
        );

        assertEquals(
            "Invalid configuration in %s: %s".formatted(configFile, detail),
            exception.getMessage()
        );
    }

    private Path write(String content) throws IOException {
        Path configFile = tempDir.resolve("sqlcj.yaml");

        Files.writeString(configFile, content);

        return configFile;
    }
}
