package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Executes the compiler pipeline end to end against a real PostgreSQL
 * instance: the accepted schema snapshot is run as PostgreSQL DDL, the
 * generated Java is compiled, and the generated classes are executed through
 * the JDBC runtime.
 *
 * <p>The whole class is skipped when Docker is unavailable.
 */
@EnabledIf("dockerAvailable")
class PostgresIntegrationTest {

    private static final String SCHEMA = """
        CREATE TABLE users
        (
            id         BIGINT PRIMARY KEY,
            code       INTEGER NOT NULL,
            score      SMALLINT,
            name       VARCHAR(255),
            bio        TEXT,
            active     BOOLEAN,
            birth_date DATE,
            created_at TIMESTAMP,
            balance    DECIMAL(10, 2)
        );
        """;

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();

        postgresDataSource.setUrl(postgres.getJdbcUrl());
        postgresDataSource.setUser(postgres.getUsername());
        postgresDataSource.setPassword(postgres.getPassword());

        dataSource = postgresDataSource;
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Recreates the database state from the same schema snapshot text the
     * compiler accepts, so the snapshot is proven to be valid PostgreSQL DDL.
     */
    @BeforeEach
    void resetDatabase() throws Exception {
        execute("DROP TABLE IF EXISTS users");
        execute(SCHEMA);
    }

    @Test
    void shouldExecuteGeneratedOneQueryAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: GetUser :one
            SELECT id, code, score, name, bio, active, birth_date, created_at, balance
            FROM users
            WHERE id = $1
              AND active = $2;
            """);

        execute("""
            INSERT INTO users
                (id, code, score, name, bio, active, birth_date, created_at, balance)
            VALUES
                (1, 42, 7, 'Alice', 'first user', TRUE, DATE '1990-01-15',
                 TIMESTAMP '2026-01-01 10:00:00', 100.50)
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object query = newQuery(classLoader, "GetUser");

            Method method = query.getClass().getMethod(
                "getUser",
                Long.class,
                Boolean.class
            );

            Object result = method.invoke(query, 1L, Boolean.TRUE);

            assertNotNull(result);

            assertEquals(
                List.of(
                    "id",
                    "code",
                    "score",
                    "name",
                    "bio",
                    "active",
                    "birth_date",
                    "created_at",
                    "balance"
                ),
                recordComponentNames(result)
            );

            assertEquals(1L, component(result, "id"));
            assertEquals(42, component(result, "code"));
            assertEquals((short) 7, component(result, "score"));
            assertEquals("Alice", component(result, "name"));
            assertEquals("first user", component(result, "bio"));
            assertEquals(true, component(result, "active"));
            assertEquals(LocalDate.of(1990, 1, 15), component(result, "birth_date"));
            assertEquals(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                component(result, "created_at")
            );
            assertEquals(
                new BigDecimal("100.50"),
                component(result, "balance")
            );
        }
    }

    @Test
    void shouldExecuteGeneratedManyQueryAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: ListUsers :many
            SELECT id, name
            FROM users
            WHERE active = $1
            ORDER BY id;
            """);

        execute("""
            INSERT INTO users (id, code, name, active)
            VALUES
                (3, 3, 'Charlie', TRUE),
                (1, 1, 'Alice', TRUE),
                (2, 2, 'Bob', FALSE),
                (4, 4, 'Dora', TRUE)
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object query = newQuery(classLoader, "ListUsers");

            Method method = query.getClass().getMethod(
                "listUsers",
                Boolean.class
            );

            Object result = method.invoke(query, Boolean.TRUE);

            assertInstanceOf(List.class, result);

            List<?> users = (List<?>) result;

            assertEquals(3, users.size());

            assertEquals(List.of("id", "name"), recordComponentNames(users.getFirst()));

            assertEquals(1L, component(users.get(0), "id"));
            assertEquals("Alice", component(users.get(0), "name"));
            assertEquals(3L, component(users.get(1), "id"));
            assertEquals("Charlie", component(users.get(1), "name"));
            assertEquals(4L, component(users.get(2), "id"));
            assertEquals("Dora", component(users.get(2), "name"));
        }
    }

    @Test
    void shouldExecuteGeneratedWriteAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUser :exec
            INSERT INTO users (id, code, name, birth_date, created_at, balance)
            VALUES ($1, $2, $3, $4, $5, $6);

            -- name: GetUser :one
            SELECT id, code, name, birth_date, created_at, balance
            FROM users
            WHERE id = $1;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object insert = newQuery(classLoader, "InsertUser");

            Method insertMethod = insert.getClass().getMethod(
                "insertUser",
                Long.class,
                Integer.class,
                String.class,
                LocalDate.class,
                LocalDateTime.class,
                BigDecimal.class
            );

            Object affectedRows = insertMethod.invoke(
                insert,
                5L,
                42,
                "Alice",
                LocalDate.of(1990, 1, 15),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                new BigDecimal("100.50")
            );

            assertEquals(1, affectedRows);

            Object query = newQuery(classLoader, "GetUser");

            Method queryMethod = query.getClass().getMethod("getUser", Long.class);

            Object result = queryMethod.invoke(query, 5L);

            assertNotNull(result);

            assertEquals(5L, component(result, "id"));
            assertEquals(42, component(result, "code"));
            assertEquals("Alice", component(result, "name"));
            assertEquals(LocalDate.of(1990, 1, 15), component(result, "birth_date"));
            assertEquals(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                component(result, "created_at")
            );
            assertEquals(new BigDecimal("100.50"), component(result, "balance"));
        }
    }

    /**
     * Covers null parameter binding and null result reading for every nullable
     * column type of the schema snapshot: the row is written through the
     * generated write method with null arguments and read back through the
     * generated read method.
     */
    @Test
    void shouldBindAndReadNullValuesThroughGeneratedCode() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUser :exec
            INSERT INTO users
                (id, code, score, name, bio, active, birth_date, created_at, balance)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9);

            -- name: GetUser :one
            SELECT id, code, score, name, bio, active, birth_date, created_at, balance
            FROM users
            WHERE id = $1;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object insert = newQuery(classLoader, "InsertUser");

            Method insertMethod = insert.getClass().getMethod(
                "insertUser",
                Long.class,
                Integer.class,
                Short.class,
                String.class,
                String.class,
                Boolean.class,
                LocalDate.class,
                LocalDateTime.class,
                BigDecimal.class
            );

            Object affectedRows = insertMethod.invoke(
                insert,
                6L,
                42,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );

            assertEquals(1, affectedRows);

            Object query = newQuery(classLoader, "GetUser");

            Method queryMethod = query.getClass().getMethod("getUser", Long.class);

            Object result = queryMethod.invoke(query, 6L);

            assertNotNull(result);

            assertEquals(6L, component(result, "id"));
            assertEquals(42, component(result, "code"));
            assertNull(component(result, "score"));
            assertNull(component(result, "name"));
            assertNull(component(result, "bio"));
            assertNull(component(result, "active"));
            assertNull(component(result, "birth_date"));
            assertNull(component(result, "created_at"));
            assertNull(component(result, "balance"));
        }
    }

    /**
     * Compiles the schema snapshot and the given queries, then compiles every
     * generated Java file into an isolated temporary classes directory.
     */
    private Path generateAndCompile(String queries) throws Exception {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(schemaFile, SCHEMA);
        Files.writeString(queriesFile, queries);

        Config config = new Config(
            List.of(
                new SqlConfig(
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString()
            )
        );

        try (Stream<Path> generatedFiles = Files.list(generatedDirectory.resolve("generated"))) {
            generatedFiles
                .map(Path::toString)
                .sorted()
                .forEach(arguments::add);
        }

        int compilationResult = compilerApi.run(
            null,
            null,
            null,
            arguments.toArray(new String[0])
        );

        assertEquals(0, compilationResult);

        return classesDirectory;
    }

    private URLClassLoader classLoader(Path classesDirectory) throws Exception {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            getClass().getClassLoader()
        );
    }

    private Object newQuery(URLClassLoader classLoader, String queryName) throws Exception {
        Class<?> generatedClass = Class.forName(
            "generated." + queryName,
            true,
            classLoader
        );

        Constructor<?> constructor = generatedClass.getConstructor(QueryExecutor.class);

        return constructor.newInstance(new JdbcQueryExecutor(dataSource));
    }

    private void execute(String sql) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute(sql);
        }
    }

    private List<String> recordComponentNames(Object record) {
        return Arrays.stream(record.getClass().getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    private Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }
}
