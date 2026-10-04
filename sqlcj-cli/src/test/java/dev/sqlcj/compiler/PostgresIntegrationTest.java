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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
     * A snapshot that declares every supported type through its PostgreSQL
     * spelling, including the serial spellings, which PostgreSQL fills from
     * their own sequences.
     */
    private static final String ALIAS_SCHEMA = """
        CREATE TABLE user_aliases
        (
            id           INT8 PRIMARY KEY,
            code         INT4 NOT NULL,
            score        INT2,
            label        CHARACTER VARYING(20),
            tag          CHAR(3),
            initials     CHARACTER(3),
            created_at   TIMESTAMP(3) WITHOUT TIME ZONE,
            updated_at   TIMESTAMPTZ,
            small_serial SMALLSERIAL,
            serial_two   SERIAL2,
            serial_four  SERIAL4,
            serial_eight SERIAL8
        );
        """;

    /**
     * A snapshot that declares every accepted floating-point, binary, and time
     * spelling, each of them nullable so that one row can carry values and
     * another can carry nulls.
     */
    private static final String MEASUREMENT_SCHEMA = """
        CREATE TABLE measurements
        (
            id        BIGINT PRIMARY KEY,
            amount    REAL,
            ratio     FLOAT4,
            total     DOUBLE PRECISION,
            average   FLOAT8,
            payload   BYTEA,
            opened_at TIME,
            closed_at TIME(3) WITHOUT TIME ZONE
        );
        """;

    /**
     * A snapshot declaring one nullable column of each JSON spelling, so that
     * one row can carry JSON text and another can carry nulls.
     */
    private static final String DOCUMENT_SCHEMA = """
        CREATE TABLE documents
        (
            id       BIGINT PRIMARY KEY,
            payload  JSON,
            config   JSONB
        );
        """;

    /**
     * A snapshot whose enum type is declared and then extended in the order the
     * statements state, so the modeled labels are PostgreSQL's own sort order.
     * The added labels are not used by this DDL itself, which PostgreSQL
     * forbids inside the transaction that adds them.
     */
    private static final String STAGE_EVENT_SCHEMA = """
        CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');

        ALTER TYPE stage_setting ADD VALUE 'covered' BEFORE 'outdoor';

        ALTER TYPE stage_setting ADD VALUE 'open air' AFTER 'outdoor';

        ALTER TYPE stage_setting ADD VALUE IF NOT EXISTS 'indoor';

        CREATE TABLE stage_events
        (
            id      BIGINT PRIMARY KEY,
            setting stage_setting,
            title   VARCHAR(255)
        );
        """;

    /**
     * A snapshot declaring a one-dimensional array of every element type sqlcj
     * maps: each mapped scalar type other than {@code BYTEA}, {@code JSON}, and
     * {@code JSONB}, and a declared enum type.
     */
    private static final String ARRAY_SCHEMA = """
        CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');

        CREATE TABLE array_values
        (
            id        BIGINT PRIMARY KEY,
            codes     INTEGER[],
            keys      BIGINT[],
            scores    SMALLINT[],
            flags     BOOLEAN[],
            labels    VARCHAR(20)[],
            notes     TEXT[],
            days      DATE[],
            times     TIME[],
            moments   TIMESTAMP[],
            instants  TIMESTAMPTZ[],
            amounts   NUMERIC(10, 2)[],
            ratios    REAL[],
            weights   DOUBLE PRECISION[],
            externals UUID[],
            past      stage_setting[],
            marks     CHAR(3)[]
        );
        """;

    /** The array columns of {@link #ARRAY_SCHEMA}, in schema order. */
    private static final List<String> ARRAY_COLUMNS = List.of(
        "codes",
        "keys",
        "scores",
        "flags",
        "labels",
        "notes",
        "days",
        "times",
        "moments",
        "instants",
        "amounts",
        "ratios",
        "weights",
        "externals",
        "past",
        "marks"
    );

    /**
     * A snapshot reproducing the schema constructs of sqlc's {@code booktest}
     * fixture with sqlcj's own names: two tables keyed by {@code SERIAL} with
     * an inline foreign key reference, a unique defaulted text column, an enum
     * column defaulting to a label, an integer column named {@code year}, a
     * {@code timestamptz} column, a {@code varchar[]} column defaulting to the
     * empty array, single- and two-column indexes, and a dollar-quoted
     * {@code plpgsql} function followed by one more index.
     */
    private static final String CATALOG_SCHEMA = """
        CREATE TABLE studios
        (
            studio_id SERIAL PRIMARY KEY,
            name      text NOT NULL DEFAULT ''
        );

        CREATE INDEX studios_name_idx ON studios (name);

        CREATE TYPE album_kind AS ENUM ('STUDIO', 'LIVE');

        CREATE TABLE albums
        (
            album_id   SERIAL PRIMARY KEY,
            studio_id  integer NOT NULL REFERENCES studios (studio_id),
            catalog_no text NOT NULL DEFAULT '' UNIQUE,
            kind       album_kind NOT NULL DEFAULT 'STUDIO',
            title      text NOT NULL DEFAULT '',
            year       integer NOT NULL DEFAULT 2000,
            released   timestamptz NOT NULL DEFAULT now(),
            tags       varchar[] NOT NULL DEFAULT '{}'
        );

        CREATE INDEX albums_title_year_idx ON albums (title, year);

        CREATE FUNCTION greet_listener(text) RETURNS text AS $$
        BEGIN
            RETURN CONCAT('hello ', $1);
        END;
        $$ LANGUAGE plpgsql;

        CREATE INDEX albums_kind_idx ON albums (kind);
        """;

    /**
     * Queries whose shapes mirror {@code booktest}'s {@code GetBook},
     * {@code CreateBook}, {@code UpdateBook}, and {@code UpdateBookISBN}: a
     * full-row read by key, a seven-column insert returning the full row, an
     * update of a text and an array column by key, and an update whose third
     * assignment binds {@code $4} while the key binds {@code $3}, together with
     * a read of unqualified columns over a {@code LEFT JOIN} filtered by a cast
     * array placeholder compared with {@code &&}.
     */
    private static final String CATALOG_QUERIES = """
        -- name: GetAlbum :one
        SELECT *
        FROM albums
        WHERE album_id = $1;

        -- name: CreateAlbum :one
        INSERT INTO albums (
            studio_id,
            catalog_no,
            kind,
            title,
            year,
            released,
            tags
        )
        VALUES (
            $1, $2, $3, $4, $5, $6, $7
        )
        RETURNING *;

        -- name: UpdateAlbum :exec
        UPDATE albums
        SET title = $1, tags = $2
        WHERE album_id = $3;

        -- name: UpdateAlbumCatalogNo :exec
        UPDATE albums
        SET title = $1, tags = $2, catalog_no = $4
        WHERE album_id = $3;

        -- name: ListAlbumsByTags :many
        SELECT album_id, title, name, catalog_no, tags
        FROM albums
        LEFT JOIN studios ON albums.studio_id = studios.studio_id
        WHERE tags && $1::varchar[];
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

    /** The first migration file of the migration-directory fixture. */
    private static final String REGION_MIGRATION = """
        CREATE TABLE region (
            code  text PRIMARY KEY,
            title text NOT NULL
        );
        """;

    /**
     * The second migration file, without the {@code COMMENT ON TYPE} statement
     * JSqlParser does not parse, so that the directory loads.
     */
    private static final String STAGE_MIGRATION = """
        CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');

        CREATE TABLE stages (
            id          SERIAL PRIMARY KEY,
            handle      text NOT NULL,
            legacy_code text,
            setting     stage_setting NOT NULL,
            past_settings stage_setting[],
            title       varchar(120) NOT NULL,
            region      text NOT NULL REFERENCES region (code),
            keywords    text[]
        );

        COMMENT ON TABLE stages IS 'Places where performances happen';
        COMMENT ON COLUMN stages.handle IS 'Appears in public links';
        """;

    /** The third migration file, which reshapes the table the second creates. */
    private static final String RESHAPE_STAGE_MIGRATION = """
        ALTER TABLE stages RENAME TO stage;
        ALTER TABLE stage DROP COLUMN legacy_code;
        ALTER TABLE stage ADD COLUMN opened_at TIMESTAMP NOT NULL DEFAULT now();
        """;

    /**
     * Queries over the tables the migration directory leaves, including an
     * insert that writes {@code NOW()} between its placeholders before
     * returning the generated key.
     */
    private static final String MIGRATED_QUERIES = """
        -- name: CreateRegion :one
        INSERT INTO region (code, title) VALUES ($1, $2) RETURNING *;

        -- name: GetStage :one
        SELECT id, handle, title, region, opened_at FROM stage WHERE handle = $1 AND region = $2;

        -- name: CreateStage :one
        INSERT INTO stage (handle, title, region, opened_at, setting, past_settings, keywords)
        VALUES ($1, $2, $3, NOW(), $4, $5, $6) RETURNING id;
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
        execute("DROP TABLE IF EXISTS stage");
        execute("DROP TABLE IF EXISTS region");
        execute("DROP TABLE IF EXISTS stage_events");
        execute("DROP TABLE IF EXISTS array_values");
        execute("DROP TYPE IF EXISTS stage_setting");
        execute("DROP TABLE IF EXISTS albums");
        execute("DROP TABLE IF EXISTS studios");
        execute("DROP TYPE IF EXISTS album_kind");
        execute("DROP FUNCTION IF EXISTS greet_listener(text)");
        execute("DROP TABLE IF EXISTS customer_orders");
        execute("DROP TABLE IF EXISTS customers");
        execute("DROP TABLE IF EXISTS measurements");
        execute("DROP TABLE IF EXISTS documents");
        execute("DROP TABLE IF EXISTS user_aliases");
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
     * Covers the list predicate end to end: one {@code List} argument is bound
     * as one server array and PostgreSQL returns the rows whose id the list
     * contains, in the queried order, while a null list and an empty list
     * contain no id and match no row.
     */
    @Test
    void shouldExecuteGeneratedListPredicateAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: ListUsersByIds :many
            SELECT id, name
            FROM users
            WHERE id = ANY($1)
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

            Method listByIds = repository.getClass().getMethod(
                "listUsersByIds",
                List.class
            );

            List<Long> noIds = null;

            assertEquals(List.of(), names(listByIds.invoke(repository, noIds)));
            assertEquals(List.of(), names(listByIds.invoke(repository, List.of())));

            assertEquals(
                List.of("Bob"),
                names(listByIds.invoke(repository, List.of(2L)))
            );

            assertEquals(
                List.of("Alice", "Cara"),
                names(listByIds.invoke(repository, List.of(3L, 1L)))
            );
        }
    }

    /**
     * A list predicate over an enum column binds the labels of its generated
     * constants as one server array of the declared enum type, so PostgreSQL
     * returns the rows whose value the list contains.
     */
    @Test
    void shouldExecuteGeneratedEnumListPredicateAgainstPostgres() throws Exception {
        execute(STAGE_EVENT_SCHEMA);

        Path classesDirectory = generateAndCompile(
            STAGE_EVENT_SCHEMA,
            """
                -- name: ListStageEventsBySettings :many
                SELECT id, setting, title
                FROM stage_events
                WHERE setting = ANY($1)
                ORDER BY id;
                """
        );

        execute("""
            INSERT INTO stage_events (id, setting, title)
            VALUES
                (1, 'indoor', 'Indoor Stage'),
                (2, 'covered', 'Covered Stage'),
                (3, 'outdoor', 'Outdoor Stage')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object[] constants = Class
                .forName("generated.StageSetting", true, classLoader)
                .getEnumConstants();

            Object repository = newRepository(classLoader);

            Method listBySettings = repository.getClass().getMethod(
                "listStageEventsBySettings",
                List.class
            );

            Object rows = listBySettings.invoke(
                repository,
                List.of(constants[0], constants[2])
            );

            List<Object> titles = new ArrayList<>();

            for (Object row : (List<?>) rows) {
                titles.add(component(row, "title"));
            }

            assertEquals(List.of("Indoor Stage", "Outdoor Stage"), titles);
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
     * Covers named placeholders end to end: a read whose placeholders are named
     * and whose first name repeats is generated, compiled, and executed against
     * PostgreSQL, so its method parameters follow first-occurrence order while
     * each occurrence binds at its own textual position.
     */
    @Test
    void shouldExecuteGeneratedNamedPlaceholdersAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: ListUsersByTerm :many
            SELECT id, name
            FROM users
            WHERE (name = :term OR bio = :term)
              AND id > :minId
            ORDER BY id
            OFFSET :skip LIMIT :pageSize;
            """);

        execute("""
            INSERT INTO users (id, code, name, bio)
            VALUES
                (1, 1, 'Alice', NULL),
                (2, 2, 'Alice', NULL),
                (3, 3, 'Bob', 'Alice'),
                (4, 4, 'Bob', NULL)
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method listUsersByTerm = repository.getClass().getMethod(
                "listUsersByTerm",
                String.class,
                Long.class,
                Integer.class,
                Integer.class
            );

            assertEquals(
                List.of("Alice", "Bob"),
                names(listUsersByTerm.invoke(repository, "Alice", 0L, 1, 2))
            );

            assertEquals(
                List.of("Bob"),
                names(listUsersByTerm.invoke(repository, "Alice", 2L, 0, 1))
            );
        }
    }

    /**
     * Covers the cast-typed optional filter end to end: one named placeholder
     * typed by its cast both tests whether the filter is absent and compares
     * the column, so a null argument returns every row and a value returns
     * only the matching rows.
     */
    @Test
    void shouldExecuteGeneratedCastTypedOptionalFilterAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: ListUsersByName :many
            SELECT id, name
            FROM users
            WHERE (:name::text IS NULL OR name = :name)
            ORDER BY id;
            """);

        execute("""
            INSERT INTO users (id, code, name)
            VALUES
                (1, 1, 'Alice'),
                (2, 2, 'Bob'),
                (3, 3, 'Alice')
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method listUsersByName = repository.getClass().getMethod(
                "listUsersByName",
                String.class
            );

            assertEquals(
                List.of("Alice", "Bob", "Alice"),
                names(listUsersByName.invoke(repository, new Object[] { null }))
            );

            assertEquals(
                List.of("Alice", "Alice"),
                names(listUsersByName.invoke(repository, "Alice"))
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

    /**
     * Covers a grouped count end to end: a {@code :many} read whose projection
     * is a direct column beside an aliased {@code COUNT(*)} is generated,
     * compiled, and executed against PostgreSQL, so its result record exposes
     * the grouped column and the count in projection order.
     */
    @Test
    void shouldExecuteGeneratedGroupedCountAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: CountUsersByActive :many
            SELECT active, COUNT(*) AS users
            FROM users
            GROUP BY active
            ORDER BY active;
            """);

        execute("""
            INSERT INTO users (id, code, name, active)
            VALUES
                (1, 1, 'Alice', TRUE),
                (2, 2, 'Amy', TRUE),
                (3, 3, 'Bob', FALSE)
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object result = repository
                .getClass()
                .getMethod("countUsersByActive")
                .invoke(repository);

            List<?> groups = (List<?>) result;

            assertEquals(2, groups.size());

            assertEquals(
                List.of("active", "users"),
                recordComponentNames(groups.getFirst())
            );

            assertEquals(
                List.of(Boolean.class, Long.class),
                recordComponentTypes(groups.getFirst())
            );

            assertEquals(Boolean.FALSE, component(groups.get(0), "active"));
            assertEquals(1L, component(groups.get(0), "users"));
            assertEquals(Boolean.TRUE, component(groups.get(1), "active"));
            assertEquals(2L, component(groups.get(1), "users"));
        }
    }

    /**
     * Covers a cast projection end to end: a {@code :one} read whose single
     * projection is a cast aggregate is generated, compiled, and executed
     * against PostgreSQL, so its result record exposes one nullable
     * {@code BigDecimal} component that is {@code null} when no row matches.
     */
    @Test
    void shouldExecuteGeneratedCastProjectionAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: SumBalanceByName :one
            SELECT SUM(balance)::numeric AS total
            FROM users
            WHERE name LIKE $1;
            """);

        execute("""
            INSERT INTO users (id, code, name, balance)
            VALUES
                (1, 1, 'Alice', 10.50),
                (2, 2, 'Amy', 2.25),
                (3, 3, 'Bob', 100.00)
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method sum = repository.getClass().getMethod(
                "sumBalanceByName",
                String.class
            );

            Object matching = sum.invoke(repository, "A%");

            assertEquals(List.of("total"), recordComponentNames(matching));
            assertEquals(List.of(BigDecimal.class), recordComponentTypes(matching));
            assertEquals(new BigDecimal("12.75"), component(matching, "total"));

            assertNull(component(sum.invoke(repository, "Z%"), "total"));
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
     * Covers write values that bind no placeholder: {@code DEFAULT}, a literal,
     * {@code NULL}, {@code now()}, and {@code code + 1} contribute no method
     * parameter and reach PostgreSQL as written, while the placeholders beside
     * them keep their own numbering and textual binding order.
     */
    @Test
    void shouldExecuteGeneratedNonBindingWriteValuesAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUserWithDefaults :exec
            INSERT INTO users (id, code, name, bio, serial_id, updated_at)
            VALUES ($1, $2, 'Anonymous', NULL, DEFAULT, now());

            -- name: CreateUser :one
            INSERT INTO users (bio, id, code, name, updated_at, serial_id)
            VALUES (:bio, :id, 1, 'Interleaved', now(), DEFAULT)
            RETURNING id, code, name, bio, serial_id, updated_at;

            -- name: TouchUser :exec
            UPDATE users
            SET code = code + 1,
                name = 'Touched',
                updated_at = now(),
                serial_id = DEFAULT,
                bio = $2
            WHERE id = $1;

            -- name: GetUser :one
            SELECT id, code, name, bio, serial_id, updated_at
            FROM users
            WHERE id = $1;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method insertWithDefaults = repository.getClass().getMethod(
                "insertUserWithDefaults",
                Long.class,
                Integer.class
            );

            assertEquals(1, insertWithDefaults.invoke(repository, 1L, 42));

            Method getUser = repository.getClass().getMethod("getUser", Long.class);

            Object inserted = getUser.invoke(repository, 1L);

            assertEquals(42, component(inserted, "code"));
            assertEquals("Anonymous", component(inserted, "name"));
            assertNull(component(inserted, "bio"));
            assertNotNull(component(inserted, "serialId"));
            assertInstanceOf(OffsetDateTime.class, component(inserted, "updatedAt"));

            Method createUser = repository.getClass().getMethod(
                "createUser",
                String.class,
                Long.class
            );

            Object returned = createUser.invoke(repository, "first note", 2L);

            assertNotNull(returned);

            assertEquals(
                List.of("id", "code", "name", "bio", "serialId", "updatedAt"),
                recordComponentNames(returned)
            );

            assertEquals(2L, component(returned, "id"));
            assertEquals(1, component(returned, "code"));
            assertEquals("Interleaved", component(returned, "name"));
            assertEquals("first note", component(returned, "bio"));
            assertNotNull(component(returned, "serialId"));
            assertInstanceOf(OffsetDateTime.class, component(returned, "updatedAt"));

            Method touchUser = repository.getClass().getMethod(
                "touchUser",
                Long.class,
                String.class
            );

            assertEquals(1, touchUser.invoke(repository, 1L, "touched"));

            Object touched = getUser.invoke(repository, 1L);

            assertEquals(43, component(touched, "code"));
            assertEquals("Touched", component(touched, "name"));
            assertEquals("touched", component(touched, "bio"));
        }
    }

    /**
     * Covers the accepted upsert shapes end to end: an {@code ON CONFLICT}
     * target of column names with a {@code DO NOTHING} and a {@code DO UPDATE}
     * action, with and without {@code RETURNING}, each executed over a new row
     * and then over a conflicting one.
     *
     * <p>{@code DO NOTHING} writes no row on conflict, so its {@code :exec}
     * form reports no affected row and its returning form finds none, while
     * {@code DO UPDATE} writes the proposed {@code EXCLUDED} value, the bound
     * placeholder, and the computed expression into the stored row.
     */
    @Test
    void shouldExecuteGeneratedUpsertsAgainstPostgres() throws Exception {
        Path classesDirectory = generateAndCompile("""
            -- name: InsertUserOrIgnore :exec
            INSERT INTO users (id, code, name)
            VALUES ($1, $2, $3)
            ON CONFLICT (id) DO NOTHING;

            -- name: InsertUserOrFind :optional
            INSERT INTO users (id, code, name)
            VALUES ($1, $2, $3)
            ON CONFLICT (id) DO NOTHING
            RETURNING id, name;

            -- name: UpsertUser :exec
            INSERT INTO users (id, code, name)
            VALUES (:id, :code, :name)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name,
                bio = :bio,
                code = users.code + 1;

            -- name: UpsertUserReturning :one
            INSERT INTO users (id, code, name)
            VALUES ($1, $2, $3)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name,
                bio = $4
            RETURNING id, code, name, bio;

            -- name: GetUser :one
            SELECT id, code, name, bio
            FROM users
            WHERE id = $1;
            """);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method getUser = repository.getClass().getMethod("getUser", Long.class);

            Method insertOrIgnore = repository.getClass().getMethod(
                "insertUserOrIgnore",
                Long.class,
                Integer.class,
                String.class
            );

            assertEquals(1, insertOrIgnore.invoke(repository, 1L, 10, "New"));
            assertEquals(0, insertOrIgnore.invoke(repository, 1L, 11, "Ignored"));

            Object kept = getUser.invoke(repository, 1L);

            assertEquals(10, component(kept, "code"));
            assertEquals("New", component(kept, "name"));

            Method insertOrFind = repository.getClass().getMethod(
                "insertUserOrFind",
                Long.class,
                Integer.class,
                String.class
            );

            Optional<?> inserted = assertInstanceOf(
                Optional.class,
                insertOrFind.invoke(repository, 2L, 20, "Returned")
            );

            assertEquals(2L, component(inserted.orElseThrow(), "id"));
            assertEquals("Returned", component(inserted.orElseThrow(), "name"));

            assertTrue(
                assertInstanceOf(
                    Optional.class,
                    insertOrFind.invoke(repository, 2L, 21, "Ignored")
                ).isEmpty()
            );

            Method upsertUser = repository.getClass().getMethod(
                "upsertUser",
                Long.class,
                Integer.class,
                String.class,
                String.class
            );

            assertEquals(1, upsertUser.invoke(repository, 3L, 30, "First", "first note"));

            Object created = getUser.invoke(repository, 3L);

            assertEquals(30, component(created, "code"));
            assertEquals("First", component(created, "name"));
            assertNull(component(created, "bio"));

            assertEquals(1, upsertUser.invoke(repository, 3L, 99, "Second", "second note"));

            Object updated = getUser.invoke(repository, 3L);

            assertEquals(31, component(updated, "code"));
            assertEquals("Second", component(updated, "name"));
            assertEquals("second note", component(updated, "bio"));

            Method upsertReturning = repository.getClass().getMethod(
                "upsertUserReturning",
                Long.class,
                Integer.class,
                String.class,
                String.class
            );

            Object returnedInsert = upsertReturning.invoke(
                repository,
                4L,
                40,
                "Fourth",
                "fourth note"
            );

            assertEquals(
                List.of("id", "code", "name", "bio"),
                recordComponentNames(returnedInsert)
            );

            assertEquals(4L, component(returnedInsert, "id"));
            assertEquals(40, component(returnedInsert, "code"));
            assertEquals("Fourth", component(returnedInsert, "name"));
            assertNull(component(returnedInsert, "bio"));

            Object returnedUpdate = upsertReturning.invoke(
                repository,
                4L,
                99,
                "Fourth Again",
                "fifth note"
            );

            assertEquals(4L, component(returnedUpdate, "id"));
            assertEquals(40, component(returnedUpdate, "code"));
            assertEquals("Fourth Again", component(returnedUpdate, "name"));
            assertEquals("fifth note", component(returnedUpdate, "bio"));
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
     * Proves that a snapshot declaring the PostgreSQL type spellings is valid
     * PostgreSQL DDL and round trips through generated code: the non-serial
     * columns are written through the generated write method and read back with
     * the Java types the spellings map to, while the omitted serial columns are
     * filled by their sequences.
     *
     * <p>PostgreSQL blank-pads a {@code character} value to the declared
     * length, so {@code "ab"} written into {@code CHAR(3)} reads back as
     * {@code "ab "}. A {@code timestamptz} is normalized to the session time
     * zone, so it is compared by instant.
     */
    @Test
    void shouldRoundTripPostgresTypeSpellingValues() throws Exception {
        execute(ALIAS_SCHEMA);

        Path classesDirectory = generateAndCompile(
            ALIAS_SCHEMA,
            """
                -- name: InsertUserAlias :exec
                INSERT INTO user_aliases
                    (id, code, score, label, tag, initials, created_at, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8);

                -- name: GetUserAlias :one
                SELECT id, code, score, label, tag, initials, created_at, updated_at,
                       small_serial, serial_two, serial_four, serial_eight
                FROM user_aliases
                WHERE id = $1;
                """
        );

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertUserAlias",
                Long.class,
                Integer.class,
                Short.class,
                String.class,
                String.class,
                String.class,
                LocalDateTime.class,
                OffsetDateTime.class
            );

            Object affectedRows = insertMethod.invoke(
                repository,
                8L,
                42,
                (short) 7,
                "gold",
                "ab",
                "xyz",
                LocalDateTime.of(2026, 1, 1, 10, 0),
                UPDATED_AT
            );

            assertEquals(1, affectedRows);

            Object result = repository
                .getClass()
                .getMethod("getUserAlias", Long.class)
                .invoke(repository, 8L);

            assertNotNull(result);

            assertEquals(
                List.of(
                    "id",
                    "code",
                    "score",
                    "label",
                    "tag",
                    "initials",
                    "createdAt",
                    "updatedAt",
                    "smallSerial",
                    "serialTwo",
                    "serialFour",
                    "serialEight"
                ),
                recordComponentNames(result)
            );

            assertEquals(
                List.of(
                    Long.class,
                    Integer.class,
                    Short.class,
                    String.class,
                    String.class,
                    String.class,
                    LocalDateTime.class,
                    OffsetDateTime.class,
                    Short.class,
                    Short.class,
                    Integer.class,
                    Long.class
                ),
                recordComponentTypes(result)
            );

            assertEquals(8L, component(result, "id"));
            assertEquals(42, component(result, "code"));
            assertEquals((short) 7, component(result, "score"));
            assertEquals("gold", component(result, "label"));
            assertEquals("ab ", component(result, "tag"));
            assertEquals("xyz", component(result, "initials"));
            assertEquals(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                component(result, "createdAt")
            );

            OffsetDateTime updatedAt = assertInstanceOf(
                OffsetDateTime.class,
                component(result, "updatedAt")
            );

            assertEquals(UPDATED_AT.toInstant(), updatedAt.toInstant());

            assertEquals((short) 1, component(result, "smallSerial"));
            assertEquals((short) 1, component(result, "serialTwo"));
            assertEquals(1, component(result, "serialFour"));
            assertEquals(1L, component(result, "serialEight"));
        }
    }

    /**
     * Proves that the floating-point, binary, and time spellings round trip
     * through generated code: one row is written with non-null values and one
     * with nulls, and both are read back as {@code Float}, {@code Double},
     * {@code byte[]}, and {@code LocalTime} components.
     */
    @Test
    void shouldRoundTripFloatingPointBinaryAndTimeValues() throws Exception {
        execute(MEASUREMENT_SCHEMA);

        Path classesDirectory = generateAndCompile(
            MEASUREMENT_SCHEMA,
            """
                -- name: InsertMeasurement :exec
                INSERT INTO measurements
                    (id, amount, ratio, total, average, payload, opened_at, closed_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8);

                -- name: GetMeasurement :one
                SELECT id, amount, ratio, total, average, payload, opened_at, closed_at
                FROM measurements
                WHERE id = $1;
                """
        );

        byte[] payload = { 1, 2, 3, -128 };

        LocalTime openedAt = LocalTime.of(9, 30);
        LocalTime closedAt = LocalTime.of(17, 45, 30);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertMeasurement",
                Long.class,
                Float.class,
                Float.class,
                Double.class,
                Double.class,
                byte[].class,
                LocalTime.class,
                LocalTime.class
            );

            assertEquals(
                1,
                insertMethod.invoke(
                    repository,
                    1L,
                    1.5f,
                    2.25f,
                    3.5d,
                    4.125d,
                    payload,
                    openedAt,
                    closedAt
                )
            );

            assertEquals(
                1,
                insertMethod.invoke(repository, 2L, null, null, null, null, null, null, null)
            );

            Method queryMethod = repository.getClass().getMethod("getMeasurement", Long.class);

            Object result = queryMethod.invoke(repository, 1L);

            assertNotNull(result);

            assertEquals(
                List.of(
                    "id",
                    "amount",
                    "ratio",
                    "total",
                    "average",
                    "payload",
                    "openedAt",
                    "closedAt"
                ),
                recordComponentNames(result)
            );

            assertEquals(
                List.of(
                    Long.class,
                    Float.class,
                    Float.class,
                    Double.class,
                    Double.class,
                    byte[].class,
                    LocalTime.class,
                    LocalTime.class
                ),
                recordComponentTypes(result)
            );

            assertEquals(1.5f, component(result, "amount"));
            assertEquals(2.25f, component(result, "ratio"));
            assertEquals(3.5d, component(result, "total"));
            assertEquals(4.125d, component(result, "average"));
            assertArrayEquals(
                payload,
                assertInstanceOf(byte[].class, component(result, "payload"))
            );
            assertEquals(openedAt, component(result, "openedAt"));
            assertEquals(closedAt, component(result, "closedAt"));

            Object nullResult = queryMethod.invoke(repository, 2L);

            assertNotNull(nullResult);

            assertEquals(2L, component(nullResult, "id"));
            assertNull(component(nullResult, "amount"));
            assertNull(component(nullResult, "ratio"));
            assertNull(component(nullResult, "total"));
            assertNull(component(nullResult, "average"));
            assertNull(component(nullResult, "payload"));
            assertNull(component(nullResult, "openedAt"));
            assertNull(component(nullResult, "closedAt"));
        }
    }

    /**
     * Proves that enum values round trip through generated code: the generated
     * constants carry PostgreSQL's own labels in its own sort order, a null and
     * a non-null value are written as {@code INSERT} values and read back as
     * constants of the generated enum, and an equality predicate matches by
     * label while a null argument matches no row.
     */
    @Test
    void shouldRoundTripEnumValues() throws Exception {
        execute(STAGE_EVENT_SCHEMA);

        Path classesDirectory = generateAndCompile(
            STAGE_EVENT_SCHEMA,
            """
                -- name: InsertStageEvent :exec
                INSERT INTO stage_events (id, setting, title)
                VALUES ($1, $2, $3);

                -- name: GetStageEvent :one
                SELECT id, setting, title
                FROM stage_events
                WHERE id = $1;

                -- name: FindStageEventBySetting :optional
                SELECT id, setting, title
                FROM stage_events
                WHERE setting = $1;
                """
        );

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> stageSetting = Class.forName(
                "generated.StageSetting",
                true,
                classLoader
            );

            assertTrue(stageSetting.isEnum());

            Object[] constants = stageSetting.getEnumConstants();

            assertEquals(
                List.of("INDOOR", "COVERED", "OUTDOOR", "OPEN_AIR"),
                Arrays.stream(constants).map(Object::toString).toList()
            );

            Method label = stageSetting.getMethod("label");

            List<Object> labels = new ArrayList<>();

            for (Object constant : constants) {
                labels.add(label.invoke(constant));
            }

            assertEquals(enumLabels("stage_setting"), labels);

            Object covered = constants[1];

            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertStageEvent",
                Long.class,
                stageSetting,
                String.class
            );

            assertEquals(1, insertMethod.invoke(repository, 1L, covered, "Covered Stage"));
            assertEquals(1, insertMethod.invoke(repository, 2L, null, null));

            Method queryMethod = repository.getClass().getMethod("getStageEvent", Long.class);

            Object result = queryMethod.invoke(repository, 1L);

            assertNotNull(result);

            assertEquals(
                List.of("id", "setting", "title"),
                recordComponentNames(result)
            );

            assertEquals(
                List.of(Long.class, stageSetting, String.class),
                recordComponentTypes(result)
            );

            assertEquals(covered, component(result, "setting"));
            assertEquals("Covered Stage", component(result, "title"));

            Object nullResult = queryMethod.invoke(repository, 2L);

            assertNotNull(nullResult);

            assertEquals(2L, component(nullResult, "id"));
            assertNull(component(nullResult, "setting"));

            Method findMethod = repository.getClass().getMethod(
                "findStageEventBySetting",
                stageSetting
            );

            Optional<?> found = assertInstanceOf(
                Optional.class,
                findMethod.invoke(repository, covered)
            );

            assertEquals(1L, component(found.orElseThrow(), "id"));

            assertTrue(
                assertInstanceOf(
                    Optional.class,
                    findMethod.invoke(repository, new Object[] { null })
                ).isEmpty()
            );
        }
    }

    /**
     * Proves that a one-dimensional array round trips through generated code
     * for every element type sqlcj maps: a non-empty list holding a
     * {@code null} element, an empty list, and a {@code null} list are written
     * and read back as {@code List} components of the element's Java type. A
     * {@code TIMESTAMP WITH TIME ZONE} element is compared by instant, because
     * PostgreSQL normalizes the stored value to the session time zone, and a
     * {@code CHAR(n)} element is compared with its stored value, which
     * PostgreSQL pads to the declared length.
     */
    @Test
    void shouldRoundTripArrayValues() throws Exception {
        execute(ARRAY_SCHEMA);

        Path classesDirectory = generateAndCompile(
            ARRAY_SCHEMA,
            """
                -- name: InsertArrayValues :exec
                INSERT INTO array_values (
                    id, codes, keys, scores, flags, labels, notes, days, times,
                    moments, instants, amounts, ratios, weights, externals, past,
                    marks
                )
                VALUES (
                    $1, $2, $3, $4, $5, $6, $7, $8, $9,
                    $10, $11, $12, $13, $14, $15, $16, $17
                );

                -- name: GetArrayValues :one
                SELECT *
                FROM array_values
                WHERE id = $1;

                -- name: FindArrayValuesByMarks :one
                SELECT id
                FROM array_values
                WHERE marks = $1;
                """
        );

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> stageSetting = Class.forName(
                "generated.StageSetting",
                true,
                classLoader
            );

            Object[] constants = stageSetting.getEnumConstants();

            List<List<?>> values = List.of(
                Arrays.asList(1, null, 3),
                Arrays.asList(10L, null, 30L),
                Arrays.asList((short) 1, null, (short) 3),
                Arrays.asList(true, null, false),
                Arrays.asList("first", null, "second"),
                Arrays.asList("note one", null, "note two"),
                Arrays.asList(LocalDate.of(2026, 1, 15), null, LocalDate.of(2026, 2, 20)),
                Arrays.asList(LocalTime.of(10, 15, 30), null, LocalTime.of(23, 59)),
                Arrays.asList(
                    LocalDateTime.of(2026, 1, 1, 10, 0),
                    null,
                    LocalDateTime.of(2026, 2, 1, 11, 30)
                ),
                Arrays.asList(
                    OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                    null,
                    OffsetDateTime.of(2026, 2, 1, 11, 30, 0, 0, ZoneOffset.ofHours(2))
                ),
                Arrays.asList(new BigDecimal("100.50"), null, new BigDecimal("200.25")),
                Arrays.asList(1.5f, null, 2.5f),
                Arrays.asList(1.25, null, 2.5),
                Arrays.asList(
                    UUID.fromString("0f2a0e2e-95f0-4a0f-8f07-2b0f2f3c9a11"),
                    null,
                    UUID.fromString("7c9a1f4e-6b3d-4a5e-9f2b-1d8c0a6e4b22")
                ),
                Arrays.asList(constants[0], null, constants[1]),
                Arrays.asList("ab", null, "c")
            );

            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertArrayValues",
                arrayParameterTypes()
            );

            assertEquals(1, insertMethod.invoke(repository, arguments(1L, values)));
            assertEquals(1, insertMethod.invoke(repository, arguments(2L, emptyLists())));
            assertEquals(1, insertMethod.invoke(repository, arguments(3L, nullLists())));

            Method queryMethod = repository.getClass().getMethod("getArrayValues", Long.class);

            Object filled = queryMethod.invoke(repository, 1L);

            List<String> componentNames = new ArrayList<>();

            componentNames.add("id");
            componentNames.addAll(ARRAY_COLUMNS);

            assertEquals(componentNames, recordComponentNames(filled));

            for (String column : ARRAY_COLUMNS) {
                assertInstanceOf(List.class, component(filled, column));
            }

            for (int index = 0; index < ARRAY_COLUMNS.size(); index++) {
                String column = ARRAY_COLUMNS.get(index);

                if (column.equals("instants") || column.equals("marks")) {
                    continue;
                }

                assertEquals(values.get(index), component(filled, column), column);
            }

            List<?> instants = (List<?>) component(filled, "instants");

            assertEquals(3, instants.size());
            assertNull(instants.get(1));

            assertTrue(
                ((OffsetDateTime) values.get(9).get(0))
                    .isEqual((OffsetDateTime) instants.get(0))
            );

            assertTrue(
                ((OffsetDateTime) values.get(9).get(2))
                    .isEqual((OffsetDateTime) instants.get(2))
            );

            assertEquals(
                Arrays.asList("ab ", null, "c  "),
                component(filled, "marks"),
                "marks"
            );

            Method findByMarks = repository.getClass().getMethod(
                "findArrayValuesByMarks",
                List.class
            );

            assertEquals(
                1L,
                component(findByMarks.invoke(repository, values.getLast()), "id")
            );

            Object empty = queryMethod.invoke(repository, 2L);

            for (String column : ARRAY_COLUMNS) {
                assertEquals(List.of(), component(empty, column), column);
            }

            Object missing = queryMethod.invoke(repository, 3L);

            for (String column : ARRAY_COLUMNS) {
                assertNull(component(missing, column), column);
            }
        }
    }

    /** The parameter types of the generated array insert: an id and 16 lists. */
    private Class<?>[] arrayParameterTypes() {
        Class<?>[] parameterTypes = new Class<?>[ARRAY_COLUMNS.size() + 1];

        parameterTypes[0] = Long.class;

        Arrays.fill(parameterTypes, 1, parameterTypes.length, List.class);

        return parameterTypes;
    }

    private Object[] arguments(long id, List<List<?>> values) {
        List<Object> arguments = new ArrayList<>();

        arguments.add(id);
        arguments.addAll(values);

        return arguments.toArray();
    }

    private List<List<?>> emptyLists() {
        return repeated(List.of());
    }

    private List<List<?>> nullLists() {
        return repeated(null);
    }

    /** One list per array column, each the same value. */
    private List<List<?>> repeated(List<?> value) {
        List<List<?>> lists = new ArrayList<>();

        for (int index = 0; index < ARRAY_COLUMNS.size(); index++) {
            lists.add(value);
        }

        return lists;
    }

    /**
     * Proves that JSON text round trips through generated code: one row is
     * written with JSON values and one with nulls, both are read back as
     * {@code String} components, and a {@code JSONB} equality predicate matches
     * by value rather than by text. {@code JSON} keeps the text as written and
     * {@code JSONB} reads back as PostgreSQL normalizes it. The equality
     * predicate is proven on {@code JSONB} alone, because PostgreSQL defines no
     * {@code json = json} operator.
     */
    @Test
    void shouldRoundTripJsonValues() throws Exception {
        execute(DOCUMENT_SCHEMA);

        Path classesDirectory = generateAndCompile(
            DOCUMENT_SCHEMA,
            """
                -- name: InsertDocument :exec
                INSERT INTO documents (id, payload, config)
                VALUES ($1, $2, $3);

                -- name: GetDocument :one
                SELECT id, payload, config
                FROM documents
                WHERE id = $1;

                -- name: FindDocumentByConfig :optional
                SELECT id, payload, config
                FROM documents
                WHERE config = $1;
                """
        );

        String payload = "{\"b\":  2,\n \"a\": 1}";
        String config = "{\"b\": 2, \"a\": 1}";

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method insertMethod = repository.getClass().getMethod(
                "insertDocument",
                Long.class,
                String.class,
                String.class
            );

            assertEquals(1, insertMethod.invoke(repository, 1L, payload, config));
            assertEquals(1, insertMethod.invoke(repository, 2L, null, null));

            Method queryMethod = repository.getClass().getMethod("getDocument", Long.class);

            Object result = queryMethod.invoke(repository, 1L);

            assertNotNull(result);

            assertEquals(
                List.of("id", "payload", "config"),
                recordComponentNames(result)
            );

            assertEquals(
                List.of(Long.class, String.class, String.class),
                recordComponentTypes(result)
            );

            assertEquals(payload, component(result, "payload"));
            assertEquals("{\"a\": 1, \"b\": 2}", component(result, "config"));

            Object nullResult = queryMethod.invoke(repository, 2L);

            assertNotNull(nullResult);

            assertEquals(2L, component(nullResult, "id"));
            assertNull(component(nullResult, "payload"));
            assertNull(component(nullResult, "config"));

            Method findMethod = repository.getClass().getMethod(
                "findDocumentByConfig",
                String.class
            );

            Optional<?> found = assertInstanceOf(
                Optional.class,
                findMethod.invoke(repository, "{\"a\":1,   \"b\":2}")
            );

            assertEquals(1L, component(found.orElseThrow(), "id"));

            assertTrue(
                assertInstanceOf(
                    Optional.class,
                    findMethod.invoke(repository, new Object[] { null })
                ).isEmpty()
            );
        }
    }

    /**
     * Proves that the schema constructs and query shapes of sqlc's
     * {@code booktest} fixture generate typed Java and run against PostgreSQL:
     * the enum type becomes a Java enum keeping its PostgreSQL labels, the full
     * row of the referencing table carries the enum, the
     * {@code TIMESTAMP WITH TIME ZONE}, and the {@code varchar[]} column as
     * typed components, the seven-column insert binds them in placeholder
     * order, and the update whose assignments use {@code $1}, {@code $2}, and
     * {@code $4} around the {@code $3} key exposes its parameters in
     * placeholder order while binding them in textual order.
     *
     * <p>A read of unqualified columns over {@code albums LEFT JOIN studios}
     * resolves each of them to its own table and takes one {@code List}
     * parameter for the {@code varchar[]} cast placeholder its {@code &&}
     * predicate compares with the array column, so an overlapping tag list
     * returns the album beside its studio name and a non-overlapping one
     * returns nothing.
     */
    @Test
    void shouldExecuteGeneratedCatalogQueriesOverBooktestSchemaConstructs() throws Exception {
        execute(CATALOG_SCHEMA);
        execute("INSERT INTO studios (studio_id, name) VALUES (1, 'Blue Note')");

        Path classesDirectory = generateAndCompile(CATALOG_SCHEMA, CATALOG_QUERIES);

        OffsetDateTime released = OffsetDateTime.of(
            1963,
            4,
            1,
            20,
            30,
            0,
            0,
            ZoneOffset.ofHours(-5)
        );

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Class<?> albumKind = Class.forName("generated.AlbumKind", true, classLoader);

            assertTrue(albumKind.isEnum());

            Object[] constants = albumKind.getEnumConstants();

            assertEquals(
                List.of("STUDIO", "LIVE"),
                Arrays.stream(constants).map(Object::toString).toList()
            );

            Method label = albumKind.getMethod("label");

            List<Object> labels = new ArrayList<>();

            for (Object constant : constants) {
                labels.add(label.invoke(constant));
            }

            assertEquals(enumLabels("album_kind"), labels);

            Object live = constants[1];

            Object repository = newRepository(classLoader);

            Method createMethod = repository.getClass().getMethod(
                "createAlbum",
                Integer.class,
                String.class,
                albumKind,
                String.class,
                Integer.class,
                OffsetDateTime.class,
                List.class
            );

            Object created = createMethod.invoke(
                repository,
                1,
                "BN-1001",
                live,
                "Night Sessions",
                1963,
                released,
                List.of("jazz", "live")
            );

            assertNotNull(created);

            assertEquals(
                List.of(
                    "albumId",
                    "studioId",
                    "catalogNo",
                    "kind",
                    "title",
                    "year",
                    "released",
                    "tags"
                ),
                recordComponentNames(created)
            );

            assertEquals(
                List.of(
                    Integer.class,
                    Integer.class,
                    String.class,
                    albumKind,
                    String.class,
                    Integer.class,
                    OffsetDateTime.class,
                    List.class
                ),
                recordComponentTypes(created)
            );

            Object albumId = component(created, "albumId");

            assertNotNull(albumId);
            assertEquals(1, component(created, "studioId"));
            assertEquals(live, component(created, "kind"));
            assertEquals(List.of("jazz", "live"), component(created, "tags"));

            assertTrue(released.isEqual((OffsetDateTime) component(created, "released")));

            Method getMethod = repository.getClass().getMethod("getAlbum", Integer.class);

            Object read = getMethod.invoke(repository, albumId);

            assertNotNull(read);
            assertEquals("BN-1001", component(read, "catalogNo"));
            assertEquals("Night Sessions", component(read, "title"));
            assertEquals(1963, component(read, "year"));
            assertEquals(live, component(read, "kind"));
            assertEquals(List.of("jazz", "live"), component(read, "tags"));

            assertTrue(released.isEqual((OffsetDateTime) component(read, "released")));

            Method updateMethod = repository.getClass().getMethod(
                "updateAlbum",
                String.class,
                List.class,
                Integer.class
            );

            assertEquals(
                1,
                updateMethod.invoke(
                    repository,
                    "Night Sessions, Complete",
                    List.of("jazz"),
                    albumId
                )
            );

            Object updated = getMethod.invoke(repository, albumId);

            assertEquals("Night Sessions, Complete", component(updated, "title"));
            assertEquals(List.of("jazz"), component(updated, "tags"));
            assertEquals("BN-1001", component(updated, "catalogNo"));

            Method updateCatalogNoMethod = repository.getClass().getMethod(
                "updateAlbumCatalogNo",
                String.class,
                List.class,
                Integer.class,
                String.class
            );

            assertEquals(
                List.of(String.class, List.class, Integer.class, String.class),
                List.of(updateCatalogNoMethod.getParameterTypes())
            );

            assertEquals(
                1,
                updateCatalogNoMethod.invoke(
                    repository,
                    "Night Sessions, Vol. 2",
                    List.of("jazz", "reissue"),
                    albumId,
                    "BN-1002"
                )
            );

            Object reissued = getMethod.invoke(repository, albumId);

            assertEquals("Night Sessions, Vol. 2", component(reissued, "title"));
            assertEquals(List.of("jazz", "reissue"), component(reissued, "tags"));
            assertEquals("BN-1002", component(reissued, "catalogNo"));
            assertEquals(live, component(reissued, "kind"));

            Method listByTagsMethod = repository.getClass().getMethod(
                "listAlbumsByTags",
                List.class
            );

            assertEquals(
                List.of(List.class),
                List.of(listByTagsMethod.getParameterTypes())
            );

            List<?> overlapping = (List<?>) listByTagsMethod.invoke(
                repository,
                List.of("reissue", "classical")
            );

            assertEquals(1, overlapping.size());

            Object tagged = overlapping.get(0);

            assertEquals(
                List.of("albumId", "title", "name", "catalogNo", "tags"),
                recordComponentNames(tagged)
            );

            assertEquals(
                List.of(
                    Integer.class,
                    String.class,
                    String.class,
                    String.class,
                    List.class
                ),
                recordComponentTypes(tagged)
            );

            assertEquals(albumId, component(tagged, "albumId"));
            assertEquals("Night Sessions, Vol. 2", component(tagged, "title"));
            assertEquals("Blue Note", component(tagged, "name"));
            assertEquals("BN-1002", component(tagged, "catalogNo"));
            assertEquals(List.of("jazz", "reissue"), component(tagged, "tags"));

            assertEquals(
                List.of(),
                listByTagsMethod.invoke(repository, List.of("classical"))
            );
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
     * Covers a left join end to end: the read is generated, compiled, and
     * executed against PostgreSQL, so a matched row carries the joined values
     * while an unmatched row reads every component of the left-joined source as
     * {@code null}, including its {@code NOT NULL} columns.
     */
    @Test
    void shouldExecuteGeneratedLeftJoinAgainstPostgres() throws Exception {
        execute(CONSTRAINT_SCHEMA);

        Path classesDirectory = generateAndCompile(
            CONSTRAINT_SCHEMA,
            """
                -- name: ListCustomerOrders :many
                SELECT c.id, c.name, o.id AS order_id, o.quantity
                FROM customers c
                LEFT JOIN customer_orders o ON o.customer_id = c.id
                ORDER BY c.id;
                """
        );

        execute("INSERT INTO customers (id, name) VALUES (1, 'Alice'), (2, 'Bob')");
        execute("INSERT INTO customer_orders (id, customer_id, quantity) VALUES (10, 1, 3)");

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Method method = repository.getClass().getMethod("listCustomerOrders");

            Object result = method.invoke(repository);

            assertInstanceOf(List.class, result);

            List<?> rows = (List<?>) result;

            assertEquals(2, rows.size());

            assertEquals(
                List.of("id", "name", "orderId", "quantity"),
                recordComponentNames(rows.getFirst())
            );

            assertEquals(1L, component(rows.get(0), "id"));
            assertEquals("Alice", component(rows.get(0), "name"));
            assertEquals(10L, component(rows.get(0), "orderId"));
            assertEquals(3, component(rows.get(0), "quantity"));

            assertEquals(2L, component(rows.get(1), "id"));
            assertEquals("Bob", component(rows.get(1), "name"));
            assertNull(component(rows.get(1), "orderId"));
            assertNull(component(rows.get(1), "quantity"));
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

    /**
     * Runs a migration directory end to end: PostgreSQL applies the same files
     * the compiler loads as its schema source, in file-name order, and the
     * generated code writes and reads the migrated tables through the renamed
     * table and its added column.
     *
     * <p>The insert whose {@code VALUES} list writes {@code NOW()} between its
     * placeholders binds the remaining columns in placeholder order, with the
     * generated enum and the two array columns among them, and returns the
     * generated key, so PostgreSQL stores the bound values and its own
     * timestamp.
     */
    @Test
    void shouldExecuteGeneratedCodeOverAMigratedSchemaAgainstPostgres() throws Exception {
        Path migrations = Files.createDirectories(tempDir.resolve("migrations"));

        Files.writeString(migrations.resolve("001_region.sql"), REGION_MIGRATION);
        Files.writeString(migrations.resolve("002_stage.sql"), STAGE_MIGRATION);
        Files.writeString(
            migrations.resolve("003_reshape_stage.sql"),
            RESHAPE_STAGE_MIGRATION
        );

        List<Path> migrationFiles;

        try (Stream<Path> files = Files.list(migrations)) {
            migrationFiles = files.sorted().toList();
        }

        for (Path migrationFile : migrationFiles) {
            execute(Files.readString(migrationFile));
        }

        Path classesDirectory = generateAndCompile(migrations, MIGRATED_QUERIES);

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object region = repository
                .getClass()
                .getMethod("createRegion", String.class, String.class)
                .invoke(repository, "north", "North Side");

            assertNotNull(region);

            assertEquals(List.of("code", "title"), recordComponentNames(region));
            assertEquals("north", component(region, "code"));
            assertEquals("North Side", component(region, "title"));

            execute("""
                INSERT INTO stage (handle, setting, title, region)
                VALUES ('main-hall', 'indoor', 'Main Hall', 'north')
                """);

            Object stage = repository
                .getClass()
                .getMethod("getStage", String.class, String.class)
                .invoke(repository, "main-hall", "north");

            assertNotNull(stage);

            assertEquals(
                List.of("id", "handle", "title", "region", "openedAt"),
                recordComponentNames(stage)
            );

            assertEquals(
                List.of(
                    Integer.class,
                    String.class,
                    String.class,
                    String.class,
                    LocalDateTime.class
                ),
                recordComponentTypes(stage)
            );

            assertEquals(1, component(stage, "id"));
            assertEquals("main-hall", component(stage, "handle"));
            assertEquals("Main Hall", component(stage, "title"));
            assertEquals("north", component(stage, "region"));
            assertNotNull(component(stage, "openedAt"));

            Class<?> stageSetting = Class.forName(
                "generated.StageSetting",
                true,
                classLoader
            );

            Object[] settings = stageSetting.getEnumConstants();

            Method createStageMethod = repository.getClass().getMethod(
                "createStage",
                String.class,
                String.class,
                String.class,
                stageSetting,
                List.class,
                List.class
            );

            assertEquals(
                List.of(
                    String.class,
                    String.class,
                    String.class,
                    stageSetting,
                    List.class,
                    List.class
                ),
                List.of(createStageMethod.getParameterTypes())
            );

            Object createdStage = createStageMethod.invoke(
                repository,
                "west-wing",
                "West Wing",
                "north",
                settings[0],
                List.of(settings[1], settings[0]),
                List.of("seated", "historic")
            );

            assertNotNull(createdStage);

            Object createdStageId = component(createdStage, "id");

            assertNotNull(createdStageId);

            try (
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet stored = statement.executeQuery(
                    "SELECT setting, past_settings, keywords, opened_at FROM stage WHERE id = "
                        + createdStageId
                )
            ) {
                assertTrue(stored.next());

                assertEquals("indoor", stored.getString("setting"));

                assertArrayEquals(
                    new String[] { "outdoor", "indoor" },
                    (Object[]) stored.getArray("past_settings").getArray()
                );

                assertArrayEquals(
                    new String[] { "seated", "historic" },
                    (Object[]) stored.getArray("keywords").getArray()
                );

                assertNotNull(stored.getTimestamp("opened_at"));
            }
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

        Files.writeString(schemaFile, schema);

        return generateAndCompile(schemaFile, queries);
    }

    /**
     * Compiles the queries against the configured schema path, which is either
     * one schema file or a directory of migration files, then compiles every
     * generated Java file into an isolated temporary classes directory.
     */
    private Path generateAndCompile(Path schemaPath, String queries) throws Exception {
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(queriesFile, queries);

        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    schemaPath.toString(),
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

    /** The labels of one enum type in PostgreSQL's own sort order. */
    private List<Object> enumLabels(String typeName) throws Exception {
        List<Object> labels = new ArrayList<>();

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT enumlabel FROM pg_enum WHERE enumtypid = '%s'::regtype ORDER BY enumsortorder"
                    .formatted(typeName)
            )
        ) {
            while (resultSet.next()) {
                labels.add(resultSet.getString(1));
            }
        }

        return labels;
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

    private List<Class<?>> recordComponentTypes(Object record) {
        return Arrays.stream(record.getClass().getRecordComponents())
            .<Class<?>>map(RecordComponent::getType)
            .toList();
    }

    private Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }
}
