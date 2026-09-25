package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.JavaConfig;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryCardinalityException;
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
import java.lang.reflect.InvocationTargetException;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the compiler pipeline end to end against a real PostgreSQL
 * instance: the accepted schema snapshot is run as PostgreSQL DDL, the
 * generated Java is compiled, and the generated classes are executed through
 * the JDBC runtime.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class PostgresIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code UsersRepository}. */
    private static final String GROUP = "Users";

    private static final String SCHEMA = """
        CREATE TABLE users
        (
            id          BIGINT PRIMARY KEY,
            code        INTEGER NOT NULL,
            score       SMALLINT,
            name        VARCHAR(255),
            bio         TEXT,
            active      BOOLEAN,
            birth_date  DATE,
            created_at  TIMESTAMP,
            balance     DECIMAL(10, 2),
            serial_id   SERIAL,
            revision    BIGSERIAL,
            external_id UUID,
            updated_at  TIMESTAMP(3) WITH TIME ZONE
        );
        """;

    /**
     * A snapshot whose table-level foreign key and check constraints are
     * accepted and ignored by the schema parser, together with the column
     * defaults and named constraints that surround them.
     */
    private static final String CONSTRAINT_SCHEMA = """
        CREATE TABLE customers
        (
            id   BIGINT       NOT NULL,
            name VARCHAR(255) NOT NULL,
            CONSTRAINT customers_pk PRIMARY KEY (id),
            CONSTRAINT customers_name_unique UNIQUE (name)
        );

        CREATE TABLE customer_orders
        (
            id          BIGINT  NOT NULL,
            customer_id BIGINT  NOT NULL,
            quantity    INTEGER NOT NULL DEFAULT 1,
            status      VARCHAR(32) DEFAULT 'new',
            created_at  TIMESTAMP   DEFAULT now(),
            PRIMARY KEY (id),
            CONSTRAINT customer_orders_customer_fk FOREIGN KEY (customer_id)
                REFERENCES customers (id) ON DELETE CASCADE,
            CONSTRAINT customer_orders_quantity_check CHECK (quantity > 0),
            CHECK (status <> '')
        );
        """;

    /**
     * Queries used by the caller-owned transaction tests: an affected-row
     * write, a returning write, and a read.
     */
    private static final String TRANSACTION_QUERIES = """
        -- name: InsertUser :exec
        INSERT INTO users (id, code, name)
        VALUES ($1, $2, $3);

        -- name: InsertUserReturningRow :one
        INSERT INTO users (id, code, name)
        VALUES ($1, $2, $3)
        RETURNING id, name;

        -- name: ListUserNames :many
        SELECT name
        FROM users
        ORDER BY id;
        """;

    /**
     * Queries used by the cardinality test: the same predicate on the
     * non-unique {@code code} column declared with each result annotation.
     */
    private static final String CARDINALITY_QUERIES = """
        -- name: GetUserByCode :one
        SELECT id, name
        FROM users
        WHERE code = $1;

        -- name: FindUserByCode :optional
        SELECT id, name
        FROM users
        WHERE code = $1;

        -- name: ListUsersByCode :many
        SELECT id, name
        FROM users
        WHERE code = $1
        ORDER BY id;
        """;

    private static final UUID EXTERNAL_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.of(
        2026,
        1,
        1,
        10,
        0,
        0,
        0,
        ZoneOffset.ofHours(3)
    );

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();

        if (!available && Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            throw new IllegalStateException(
                "Docker is unavailable, but " + REQUIRE_DOCKER_PROPERTY + " requires these tests to run."
            );
        }

        return available;
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
        execute("DROP TABLE IF EXISTS customer_orders");
        execute("DROP TABLE IF EXISTS customers");
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
            Object repository = newRepository(classLoader);

            Method method = repository.getClass().getMethod(
                "getUser",
                Long.class,
                Boolean.class
            );

            Object result = method.invoke(repository, 1L, Boolean.TRUE);

            assertNotNull(result);

            assertEquals(
                List.of(
                    "id",
                    "code",
                    "score",
                    "name",
                    "bio",
                    "active",
                    "birthDate",
                    "createdAt",
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
            assertEquals(LocalDate.of(1990, 1, 15), component(result, "birthDate"));
            assertEquals(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                component(result, "createdAt")
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
            Object repository = newRepository(classLoader);

            Method method = repository.getClass().getMethod(
                "listUsers",
                Boolean.class
            );

            Object result = method.invoke(repository, Boolean.TRUE);

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

    /**
     * Covers the text and null predicates end to end: a {@code LIKE} pattern on
     * a {@code VARCHAR} column, a case-insensitive {@code ILIKE} pattern on a
     * {@code TEXT} column, and both null tests are generated, compiled, and
     * executed against PostgreSQL.
     */
    @Test
    void shouldExecuteGeneratedTextAndNullPredicatesAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: SearchUsersByName :many
            SELECT id, name
            FROM users
            WHERE name LIKE $1
            ORDER BY id;

            -- name: SearchUsersByBio :many
            SELECT id, name
            FROM users
            WHERE bio LIKE $1
            ORDER BY id;

            -- name: SearchUsersByBioIgnoringCase :many
            SELECT u.id, u.name
            FROM users u
            WHERE u.bio ILIKE $1
            ORDER BY u.id;

            -- name: ListUsersWithoutBio :many
            SELECT id, name
            FROM users
            WHERE bio IS NULL
            ORDER BY id;

            -- name: ListUsersWithBio :many
            SELECT id, name
            FROM users u
            WHERE u.bio IS NOT NULL
            ORDER BY id;
            """);

        execute("""
            INSERT INTO users (id, code, name, bio)
            VALUES
                (1, 1, 'Alice', 'Writes POETRY'),
                (2, 2, 'Albert', NULL),
                (3, 3, 'Bob', 'writes poetry too')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method searchByName = repository.getClass().getMethod(
                "searchUsersByName",
                String.class
            );

            Method searchByBio = repository.getClass().getMethod(
                "searchUsersByBio",
                String.class
            );

            Method searchByBioIgnoringCase = repository.getClass().getMethod(
                "searchUsersByBioIgnoringCase",
                String.class
            );

            assertEquals(
                List.of("Alice", "Albert"),
                names(searchByName.invoke(repository, "Al%"))
            );

            assertEquals(
                List.of("Alice"),
                names(searchByBio.invoke(repository, "%POETRY%"))
            );

            assertEquals(
                List.of("Alice", "Bob"),
                names(searchByBioIgnoringCase.invoke(repository, "%POETRY%"))
            );

            assertEquals(
                List.of("Albert"),
                names(
                    repository
                        .getClass()
                        .getMethod("listUsersWithoutBio")
                        .invoke(repository)
                )
            );

            assertEquals(
                List.of("Alice", "Bob"),
                names(
                    repository
                        .getClass()
                        .getMethod("listUsersWithBio")
                        .invoke(repository)
                )
            );
        }
    }

    /**
     * Covers the range predicates end to end: a {@code BETWEEN} range and a
     * {@code NOT BETWEEN} range whose bounds use out-of-order placeholder
     * indexes are generated, compiled, and executed against PostgreSQL, so a
     * swapped binding order would change the returned names.
     */
    @Test
    void shouldExecuteGeneratedRangePredicatesAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: ListUsersInCodeRange :many
            SELECT id, name
            FROM users
            WHERE code BETWEEN $1 AND $2
            ORDER BY id;

            -- name: ListUsersOutsideCodeRange :many
            SELECT id, name
            FROM users
            WHERE code NOT BETWEEN $2 AND $1
            ORDER BY id;
            """);

        execute("""
            INSERT INTO users (id, code, name)
            VALUES
                (1, 5, 'Alice'),
                (2, 20, 'Bob'),
                (3, 40, 'Cara')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method inRange = repository.getClass().getMethod(
                "listUsersInCodeRange",
                Integer.class,
                Integer.class
            );

            Method outsideRange = repository.getClass().getMethod(
                "listUsersOutsideCodeRange",
                Integer.class,
                Integer.class
            );

            assertEquals(
                List.of("Bob"),
                names(inRange.invoke(repository, 10, 30))
            );

            assertEquals(
                List.of("Alice", "Cara"),
                names(outsideRange.invoke(repository, 30, 10))
            );
        }
    }

    /**
     * Covers pagination end to end: a {@code LIMIT ... OFFSET ...} page and the
     * same page written as {@code OFFSET ... LIMIT ...} are generated,
     * compiled, and executed against PostgreSQL, so a swapped binding order
     * would change the returned page.
     */
    @Test
    void shouldExecuteGeneratedPaginationAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: ListUserPage :many
            SELECT id, name
            FROM users
            ORDER BY id
            LIMIT $1 OFFSET $2;

            -- name: ListUserPageWithLeadingOffset :many
            SELECT id, name
            FROM users
            ORDER BY id
            OFFSET $2 LIMIT $1;
            """);

        execute("""
            INSERT INTO users (id, code, name)
            VALUES
                (1, 1, 'Alice'),
                (2, 2, 'Bob'),
                (3, 3, 'Cara'),
                (4, 4, 'Dora')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method page = repository.getClass().getMethod(
                "listUserPage",
                Integer.class,
                Integer.class
            );

            Method pageWithLeadingOffset = repository.getClass().getMethod(
                "listUserPageWithLeadingOffset",
                Integer.class,
                Integer.class
            );

            assertEquals(
                List.of("Bob", "Cara"),
                names(page.invoke(repository, 2, 1))
            );

            assertEquals(
                List.of("Bob", "Cara"),
                names(pageWithLeadingOffset.invoke(repository, 2, 1))
            );
        }
    }

    /**
     * Covers the scalar count end to end: a {@code :one} count read is
     * generated, compiled, and executed against PostgreSQL, so its result
     * record exposes one {@code Long} component that counts the matching rows
     * and is {@code 0} when none match.
     */
    @Test
    void shouldExecuteGeneratedScalarCountAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: CountUsersByName :one
            SELECT COUNT(*) AS total
            FROM users
            WHERE name LIKE $1;
            """);

        execute("""
            INSERT INTO users (id, code, name)
            VALUES
                (1, 1, 'Alice'),
                (2, 2, 'Amy'),
                (3, 3, 'Bob')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method count = repository.getClass().getMethod(
                "countUsersByName",
                String.class
            );

            Object matching = count.invoke(repository, "A%");

            assertEquals(List.of("total"), recordComponentNames(matching));

            assertEquals(
                Long.class,
                matching.getClass().getRecordComponents()[0].getType()
            );

            assertEquals(2L, component(matching, "total"));

            assertEquals(0L, component(count.invoke(repository, "Z%"), "total"));
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
            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertUser",
                Long.class,
                Integer.class,
                String.class,
                LocalDate.class,
                LocalDateTime.class,
                BigDecimal.class
            );

            Object affectedRows = insertMethod.invoke(
                repository,
                5L,
                42,
                "Alice",
                LocalDate.of(1990, 1, 15),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                new BigDecimal("100.50")
            );

            assertEquals(1, affectedRows);

            Method queryMethod = repository.getClass().getMethod("getUser", Long.class);

            Object result = queryMethod.invoke(repository, 5L);

            assertNotNull(result);

            assertEquals(5L, component(result, "id"));
            assertEquals(42, component(result, "code"));
            assertEquals("Alice", component(result, "name"));
            assertEquals(LocalDate.of(1990, 1, 15), component(result, "birthDate"));
            assertEquals(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                component(result, "createdAt")
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
                (id, code, score, name, bio, active, birth_date, created_at, balance,
                 external_id, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11);

            -- name: GetUser :one
            SELECT id, code, score, name, bio, active, birth_date, created_at, balance,
                   external_id, updated_at
            FROM users
            WHERE id = $1;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertUser",
                Long.class,
                Integer.class,
                Short.class,
                String.class,
                String.class,
                Boolean.class,
                LocalDate.class,
                LocalDateTime.class,
                BigDecimal.class,
                UUID.class,
                OffsetDateTime.class
            );

            Object affectedRows = insertMethod.invoke(
                repository,
                6L,
                42,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );

            assertEquals(1, affectedRows);

            Method queryMethod = repository.getClass().getMethod("getUser", Long.class);

            Object result = queryMethod.invoke(repository, 6L);

            assertNotNull(result);

            assertEquals(6L, component(result, "id"));
            assertEquals(42, component(result, "code"));
            assertNull(component(result, "score"));
            assertNull(component(result, "name"));
            assertNull(component(result, "bio"));
            assertNull(component(result, "active"));
            assertNull(component(result, "birthDate"));
            assertNull(component(result, "createdAt"));
            assertNull(component(result, "balance"));
            assertNull(component(result, "externalId"));
            assertNull(component(result, "updatedAt"));
        }
    }

    /**
     * Covers the PostgreSQL round trip of the serial, UUID, and
     * timestamptz mappings: every value is written through the generated
     * write method and read back through the generated read method.
     *
     * <p>PostgreSQL normalizes a {@code timestamptz} to the session time zone,
     * so the read value is compared by instant rather than by offset.
     */
    @Test
    void shouldRoundTripSerialUuidAndTimestampWithTimeZoneValues() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUser :exec
            INSERT INTO users (id, code, serial_id, revision, external_id, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6);

            -- name: GetUser :one
            SELECT id, serial_id, revision, external_id, updated_at
            FROM users
            WHERE id = $1;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertUser",
                Long.class,
                Integer.class,
                Integer.class,
                Long.class,
                UUID.class,
                OffsetDateTime.class
            );

            Object affectedRows = insertMethod.invoke(
                repository,
                7L,
                42,
                101,
                202L,
                EXTERNAL_ID,
                UPDATED_AT
            );

            assertEquals(1, affectedRows);

            Method queryMethod = repository.getClass().getMethod("getUser", Long.class);

            Object result = queryMethod.invoke(repository, 7L);

            assertNotNull(result);

            assertEquals(
                List.of("id", "serialId", "revision", "externalId", "updatedAt"),
                recordComponentNames(result)
            );

            assertEquals(7L, component(result, "id"));
            assertEquals(101, component(result, "serialId"));
            assertEquals(202L, component(result, "revision"));
            assertEquals(EXTERNAL_ID, component(result, "externalId"));

            OffsetDateTime updatedAt = assertInstanceOf(
                OffsetDateTime.class,
                component(result, "updatedAt")
            );

            assertEquals(UPDATED_AT.toInstant(), updatedAt.toInstant());
        }
    }

    /**
     * Proves that a snapshot carrying table-level foreign key and check
     * constraints, column defaults, and named constraints is valid PostgreSQL
     * DDL, compiles through the pipeline, and reads back the values the
     * database defaults produced.
     */
    @Test
    void shouldExecuteGeneratedQueryForSnapshotWithIgnoredTableConstraints() throws Exception {
        execute(CONSTRAINT_SCHEMA);

        Path classesDirectory = generateAndCompile(
            CONSTRAINT_SCHEMA,
            """
                -- name: GetCustomerOrder :one
                SELECT id, customer_id, quantity, status
                FROM customer_orders
                WHERE id = $1;
                """
        );

        execute("INSERT INTO customers (id, name) VALUES (1, 'Alice')");
        execute("INSERT INTO customer_orders (id, customer_id) VALUES (10, 1)");

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method method = repository.getClass().getMethod("getCustomerOrder", Long.class);

            Object result = method.invoke(repository, 10L);

            assertNotNull(result);

            assertEquals(
                List.of("id", "customerId", "quantity", "status"),
                recordComponentNames(result)
            );

            assertEquals(10L, component(result, "id"));
            assertEquals(1L, component(result, "customerId"));
            assertEquals(1, component(result, "quantity"));
            assertEquals("new", component(result, "status"));
        }
    }

    /**
     * Covers a returning insert: the database-generated serial values and the
     * target-table order of {@code RETURNING *} are read through the generated
     * typed result record.
     */
    @Test
    void shouldExecuteGeneratedInsertReturningAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUserReturningSerialId :one
            INSERT INTO users (id, code, name)
            VALUES ($1, $2, $3)
            RETURNING serial_id;

            -- name: InsertUserReturningRow :one
            INSERT INTO users (id, code, name)
            VALUES ($1, $2, $3)
            RETURNING *;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method serialIdMethod = repository.getClass().getMethod(
                "insertUserReturningSerialId",
                Long.class,
                Integer.class,
                String.class
            );

            Object serialIdResult = serialIdMethod.invoke(repository, 1L, 42, "Alice");

            assertNotNull(serialIdResult);

            assertEquals(List.of("serialId"), recordComponentNames(serialIdResult));
            assertEquals(1, component(serialIdResult, "serialId"));

            Method rowMethod = repository.getClass().getMethod(
                "insertUserReturningRow",
                Long.class,
                Integer.class,
                String.class
            );

            Object row = rowMethod.invoke(repository, 2L, 43, "Bob");

            assertNotNull(row);

            assertEquals(
                List.of(
                    "id",
                    "code",
                    "score",
                    "name",
                    "bio",
                    "active",
                    "birthDate",
                    "createdAt",
                    "balance",
                    "serialId",
                    "revision",
                    "externalId",
                    "updatedAt"
                ),
                recordComponentNames(row)
            );

            assertEquals(2L, component(row, "id"));
            assertEquals(43, component(row, "code"));
            assertEquals("Bob", component(row, "name"));
            assertEquals(2, component(row, "serialId"));
            assertEquals(2L, component(row, "revision"));
            assertNull(component(row, "score"));
            assertNull(component(row, "bio"));
            assertNull(component(row, "externalId"));
        }
    }

    /**
     * Covers a returning update: the repeated and out-of-order placeholders are
     * bound in textual order, the returned columns keep their declared order,
     * and a no-row update fails the {@code :one} cardinality check after the
     * statement itself executed.
     */
    @Test
    void shouldExecuteGeneratedUpdateReturningAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: RenameUser :one
            UPDATE users
            SET name = $2,
                bio = $2
            WHERE id = $1
              AND code = $3
            RETURNING bio, id, name, score;
            """);

        execute("""
            INSERT INTO users (id, code, name, bio)
            VALUES (1, 42, 'Alice', 'first user')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method method = repository.getClass().getMethod(
                "renameUser",
                Long.class,
                String.class,
                Integer.class
            );

            Object result = method.invoke(repository, 1L, "Renamed", 42);

            assertNotNull(result);

            assertEquals(
                List.of("bio", "id", "name", "score"),
                recordComponentNames(result)
            );

            assertEquals("Renamed", component(result, "bio"));
            assertEquals(1L, component(result, "id"));
            assertEquals("Renamed", component(result, "name"));
            assertNull(component(result, "score"));

            assertEquals(
                "Query 'RenameUser' in UsersRepository returned no row; expected exactly one",
                cardinalityFailure(method, repository, 404L, "Missing", 42).getMessage()
            );
        }
    }

    /**
     * Covers the shared row record: a {@code RETURNING *} write, a
     * {@code SELECT *} read, and a qualified {@code :many} full-row read of one
     * table all execute into instances of the repository's single row type.
     */
    @Test
    void shouldExecuteGeneratedFullRowQueriesIntoOneSharedRowTypeAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUserRow :one
            INSERT INTO users (id, code, name)
            VALUES ($1, $2, $3)
            RETURNING *;

            -- name: GetUserRow :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name: ListUserRows :many
            SELECT u.*
            FROM users u
            ORDER BY u.id;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object inserted = repository
                .getClass()
                .getMethod("insertUserRow", Long.class, Integer.class, String.class)
                .invoke(repository, 1L, 42, "Alice");

            Object read = repository
                .getClass()
                .getMethod("getUserRow", Long.class)
                .invoke(repository, 1L);

            List<?> listed = assertInstanceOf(
                List.class,
                repository.getClass().getMethod("listUserRows").invoke(repository)
            );

            assertNotNull(inserted);
            assertNotNull(read);
            assertEquals(1, listed.size());

            Class<?> rowType = inserted.getClass();

            assertTrue(rowType.getSimpleName().endsWith("Row"), rowType.getSimpleName());
            assertEquals(rowType, read.getClass());
            assertEquals(rowType, listed.getFirst().getClass());

            assertEquals("Alice", component(read, "name"));
            assertEquals(42, component(listed.getFirst(), "code"));
        }
    }

    /**
     * Covers a returning delete: every deleted row is returned to the
     * {@code :many} result, and a delete that matches no row returns an empty
     * list. PostgreSQL does not guarantee the returned row order, so the rows
     * are compared as a set.
     */
    @Test
    void shouldExecuteGeneratedDeleteReturningAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: DeleteUsersByActive :many
            DELETE FROM users
            WHERE active = $1
            RETURNING id, name;
            """);

        execute("""
            INSERT INTO users (id, code, name, active)
            VALUES
                (1, 1, 'Alice', TRUE),
                (2, 2, 'Bob', FALSE),
                (3, 3, 'Carol', TRUE)
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method method = repository.getClass().getMethod(
                "deleteUsersByActive",
                Boolean.class
            );

            List<?> deleted = assertInstanceOf(
                List.class,
                method.invoke(repository, Boolean.TRUE)
            );

            assertEquals(2, deleted.size());

            assertEquals(
                List.of("id", "name"),
                recordComponentNames(deleted.getFirst())
            );

            List<List<Object>> rows = new ArrayList<>();

            for (Object deletedRow : deleted) {
                rows.add(
                    List.of(
                        component(deletedRow, "id"),
                        component(deletedRow, "name")
                    )
                );
            }

            assertEquals(
                Set.of(
                    List.of(1L, "Alice"),
                    List.of(3L, "Carol")
                ),
                Set.copyOf(rows)
            );

            assertEquals(
                List.of(),
                method.invoke(repository, Boolean.TRUE)
            );
        }
    }

    /**
     * Covers the three result annotations over zero, one, and two matching rows
     * of a non-unique column, through both executor construction paths: a
     * {@code :one} query requires exactly one row, {@code :optional} accepts
     * none or one, and {@code :many} accepts any number. A failed cardinality
     * check leaves the caller-owned connection open.
     */
    @Test
    void shouldEnforceResultCardinalitiesAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile(CARDINALITY_QUERIES);

        execute("""
            INSERT INTO users (id, code, name)
            VALUES
                (1, 42, 'Alice'),
                (2, 7, 'Bob'),
                (3, 7, 'Carol')
            """);

        try (
            URLClassLoader classLoader = classLoader(classesDirectory);
            Connection connection = dataSource.getConnection()
        ) {
            assertCardinalityContract(newRepository(classLoader));

            assertCardinalityContract(
                newRepository(classLoader, new JdbcQueryExecutor(connection))
            );

            assertFalse(connection.isClosed());
        }
    }

    /**
     * Asserts the documented contract of the three cardinality annotations on
     * one generated repository instance.
     */
    private void assertCardinalityContract(Object repository) throws Exception {
        Method one = repository.getClass().getMethod("getUserByCode", Integer.class);
        Method optional = repository.getClass().getMethod("findUserByCode", Integer.class);
        Method many = repository.getClass().getMethod("listUsersByCode", Integer.class);

        Object single = one.invoke(repository, 42);

        assertEquals(1L, component(single, "id"));
        assertEquals("Alice", component(single, "name"));

        assertEquals(
            "Query 'GetUserByCode' in UsersRepository returned no row; expected exactly one",
            cardinalityFailure(one, repository, 1).getMessage()
        );

        assertEquals(
            "Query 'GetUserByCode' in UsersRepository returned more than one row; expected exactly one",
            cardinalityFailure(one, repository, 7).getMessage()
        );

        Optional<?> found = assertInstanceOf(
            Optional.class,
            optional.invoke(repository, 42)
        );

        assertEquals(1L, component(found.orElseThrow(), "id"));

        assertTrue(
            assertInstanceOf(Optional.class, optional.invoke(repository, 1)).isEmpty()
        );

        assertEquals(
            "Query 'FindUserByCode' in UsersRepository returned more than one row; expected at most one",
            cardinalityFailure(optional, repository, 7).getMessage()
        );

        assertTrue(
            assertInstanceOf(List.class, many.invoke(repository, 1)).isEmpty()
        );

        List<?> listed = assertInstanceOf(List.class, many.invoke(repository, 7));

        assertEquals(2, listed.size());
        assertEquals(2L, component(listed.get(0), "id"));
        assertEquals("Bob", component(listed.get(0), "name"));
        assertEquals(3L, component(listed.get(1), "id"));
        assertEquals("Carol", component(listed.get(1), "name"));
    }

    /**
     * Invokes a generated method that must fail its cardinality check and
     * returns the runtime exception it raised.
     */
    private QueryCardinalityException cardinalityFailure(
        Method method,
        Object repository,
        Object... arguments
    ) {
        InvocationTargetException failure = assertThrows(
            InvocationTargetException.class,
            () -> method.invoke(repository, arguments)
        );

        return assertInstanceOf(QueryCardinalityException.class, failure.getCause());
    }

    /**
     * Runs three generated operations on one caller-owned connection with
     * auto-commit disabled: an affected-row write, a returning write, and a
     * read that sees both uncommitted rows. The application's commit makes both
     * rows durable, and the connection is neither closed nor reconfigured by the
     * runtime.
     */
    @Test
    void shouldCommitGeneratedOperationsOnCallerOwnedConnection() throws Exception {
        Path classesDirectory = generateAndCompile(TRANSACTION_QUERIES);

        try (
            URLClassLoader classLoader = classLoader(classesDirectory);
            Connection connection = dataSource.getConnection()
        ) {
            connection.setAutoCommit(false);

            QueryExecutor transactional = new JdbcQueryExecutor(connection);

            assertEquals(
                1,
                insertUser(classLoader, transactional, 1L, 42, "Alice")
            );

            Object returned = insertUserReturningRow(
                classLoader,
                transactional,
                2L,
                43,
                "Bob"
            );

            assertNotNull(returned);
            assertEquals(2L, component(returned, "id"));
            assertEquals("Bob", component(returned, "name"));

            assertEquals(
                List.of("Alice", "Bob"),
                listUserNames(classLoader, transactional)
            );

            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());

            connection.commit();

            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());

            assertEquals(
                List.of("Alice", "Bob"),
                listUserNames(classLoader)
            );
        }
    }

    /**
     * Runs the same generated operations on one caller-owned connection and
     * rolls the transaction back: both writes are discarded, and the connection
     * remains open and usable for a further generated read.
     */
    @Test
    void shouldRollBackGeneratedOperationsOnCallerOwnedConnection() throws Exception {
        Path classesDirectory = generateAndCompile(TRANSACTION_QUERIES);

        try (
            URLClassLoader classLoader = classLoader(classesDirectory);
            Connection connection = dataSource.getConnection()
        ) {
            connection.setAutoCommit(false);

            QueryExecutor transactional = new JdbcQueryExecutor(connection);

            assertEquals(
                1,
                insertUser(classLoader, transactional, 3L, 44, "Carol")
            );

            assertNotNull(
                insertUserReturningRow(classLoader, transactional, 4L, 45, "Dora")
            );

            assertEquals(
                List.of("Carol", "Dora"),
                listUserNames(classLoader, transactional)
            );

            connection.rollback();

            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());

            assertEquals(
                List.of(),
                listUserNames(classLoader, transactional)
            );
        }
    }

    private Object insertUser(
        URLClassLoader classLoader,
        QueryExecutor executor,
        Long id,
        Integer code,
        String name
    ) throws Exception {
        Object repository = newRepository(classLoader, executor);

        return repository
            .getClass()
            .getMethod("insertUser", Long.class, Integer.class, String.class)
            .invoke(repository, id, code, name);
    }

    private Object insertUserReturningRow(
        URLClassLoader classLoader,
        QueryExecutor executor,
        Long id,
        Integer code,
        String name
    ) throws Exception {
        Object repository = newRepository(classLoader, executor);

        return repository
            .getClass()
            .getMethod("insertUserReturningRow", Long.class, Integer.class, String.class)
            .invoke(repository, id, code, name);
    }

    private List<Object> listUserNames(URLClassLoader classLoader) throws Exception {
        return listUserNames(classLoader, new JdbcQueryExecutor(dataSource));
    }

    private List<Object> listUserNames(
        URLClassLoader classLoader,
        QueryExecutor executor
    ) throws Exception {
        Object repository = newRepository(classLoader, executor);

        List<?> rows = (List<?>) repository
            .getClass()
            .getMethod("listUserNames")
            .invoke(repository);

        List<Object> names = new ArrayList<>();

        for (Object row : rows) {
            names.add(component(row, "name"));
        }

        return names;
    }

    /** Reads the {@code name} component of every row of a generated list result. */
    private List<Object> names(Object rows) throws Exception {
        List<Object> names = new ArrayList<>();

        for (Object row : (List<?>) rows) {
            names.add(component(row, "name"));
        }

        return names;
    }

    private Path generateAndCompile(String queries) throws Exception {
        return generateAndCompile(SCHEMA, queries);
    }

    /**
     * Compiles the given schema snapshot and queries, then compiles every
     * generated Java file into an isolated temporary classes directory.
     */
    private Path generateAndCompile(String schema, String queries) throws Exception {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(schemaFile, schema);
        Files.writeString(queriesFile, queries);

        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
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

    private Object newRepository(URLClassLoader classLoader) throws Exception {
        return newRepository(classLoader, new JdbcQueryExecutor(dataSource));
    }

    private Object newRepository(
        URLClassLoader classLoader,
        QueryExecutor executor
    ) throws Exception {
        Class<?> generatedClass = Class.forName(
            "generated." + GROUP + "Repository",
            true,
            classLoader
        );

        Constructor<?> constructor = generatedClass.getConstructor(QueryExecutor.class);

        return constructor.newInstance(executor);
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
