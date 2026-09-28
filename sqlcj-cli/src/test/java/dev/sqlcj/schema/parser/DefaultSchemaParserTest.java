package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Constraint;
import dev.sqlcj.schema.ConstraintType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSchemaParserTest {

    /** The schema the ordered-DDL tests apply their statements to. */
    private static final String BASE_SCHEMA = """
        CREATE TABLE users (
            id    BIGINT NOT NULL,
            email VARCHAR(255) UNIQUE,
            name  VARCHAR(255)
        );

        CREATE TABLE orders (
            id      BIGINT NOT NULL,
            user_id BIGINT NOT NULL
        );
        """;

    /**
     * A single-file schema in the shape of a published sqlc example: an enum
     * type, an enum column, an array column, indexes, and a dollar-quoted
     * function body followed by another statement.
     */
    private static final String SINGLE_FILE_SCHEMA = """
        CREATE TYPE shelf_state AS ENUM ('stocked', 'reserved', 'retired');

        CREATE TABLE shelves (
            shelf_id SERIAL PRIMARY KEY,
            label    text NOT NULL DEFAULT ''
        );

        CREATE TABLE records (
            record_id   SERIAL PRIMARY KEY,
            shelf_id    integer NOT NULL REFERENCES shelves (shelf_id),
            catalog_no  text NOT NULL DEFAULT '' UNIQUE,
            state       shelf_state NOT NULL DEFAULT 'stocked',
            released_on timestamp with time zone NOT NULL DEFAULT now(),
            genres      varchar[] NOT NULL DEFAULT '{}'
        );

        CREATE INDEX records_state_idx ON records (state, released_on);

        CREATE FUNCTION shelf_code(prefix text) RETURNS text AS $$
        BEGIN
            RETURN prefix || '-shelf';
        END;
        $$ LANGUAGE plpgsql;

        CREATE INDEX shelves_label_idx ON shelves (label);
        """;

    /** The first migration file of the migration-directory fixture. */
    private static final String REGION_MIGRATION = """
        CREATE TABLE region (
            code  text PRIMARY KEY,
            title text NOT NULL
        );
        """;

    /**
     * The second migration file, whose {@code COMMENT ON TYPE} statement
     * JSqlParser does not parse.
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

        COMMENT ON TYPE stage_setting IS 'Whether a stage is covered';
        COMMENT ON TABLE stages IS 'Places where performances happen';
        COMMENT ON COLUMN stages.handle IS 'Appears in public links';
        """;

    /** The third migration file, which reshapes the table the second creates. */
    private static final String RESHAPE_STAGE_MIGRATION = """
        ALTER TABLE stages RENAME TO stage;
        ALTER TABLE stage DROP COLUMN legacy_code;
        ALTER TABLE stage ADD COLUMN opened_at TIMESTAMP NOT NULL DEFAULT now();
        """;

    /** The statement of {@link #STAGE_MIGRATION} that fails to parse. */
    private static final String COMMENT_ON_TYPE_STATEMENT = "COMMENT ON TYPE stage_setting IS 'Whether a stage is covered';\n";

    private final SchemaParser parser = new DefaultSchemaParser();

    @Test
    void shouldParseTable() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(255),
                active BOOLEAN
            );
            """;

        Schema schema = parser.parse(sql);

        assertEquals(1, schema.tables().size());

        Table table = schema.tables().getFirst();

        assertEquals("users", table.name());
        assertEquals(3, table.columns().size());
    }

    @Test
    void shouldParseColumns() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(255),
                active BOOLEAN
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            new Column("id", ColumnType.BIGINT, false),
            table.columns().get(0)
        );

        assertEquals(
            new Column("name", ColumnType.VARCHAR, true),
            table.columns().get(1)
        );

        assertEquals(
            new Column("active", ColumnType.BOOLEAN, true),
            table.columns().get(2)
        );
    }

    @Test
    void shouldParseTableConstraints() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                email VARCHAR(255),
                PRIMARY KEY (id),
                UNIQUE (email)
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("id")
                ),
                new Constraint(
                    ConstraintType.UNIQUE,
                    List.of("email")
                )
            ),
            table.constraints()
        );
    }

    @Test
    void shouldParseTableWithoutConstraints() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(255)
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertTrue(table.constraints().isEmpty());
    }

    @Test
    void shouldCanonicalizeQuotedTableAndColumnNames() {
        String sql = """
            CREATE TABLE "user data" (
                "user id" BIGINT NOT NULL,
                "select" VARCHAR(255) UNIQUE,
                PRIMARY KEY ("user id")
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals("user data", table.name());

        assertEquals(
            new Column("user id", ColumnType.BIGINT, false),
            table.columns().get(0)
        );

        assertEquals(
            new Column("select", ColumnType.VARCHAR, true),
            table.columns().get(1)
        );

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.UNIQUE,
                    List.of("select")
                ),
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("user id")
                )
            ),
            table.constraints()
        );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "SERIAL, INTEGER",
            "serial, INTEGER",
            "BIGSERIAL, BIGINT",
            "bigserial, BIGINT",
            "UUID, UUID",
            "uuid, UUID",
            "TIMESTAMP WITH TIME ZONE, TIMESTAMP_WITH_TIME_ZONE",
            "timestamp with time zone, TIMESTAMP_WITH_TIME_ZONE",
            "TIMESTAMP(3) WITH TIME ZONE, TIMESTAMP_WITH_TIME_ZONE",
            "timestamp(3) with time zone, TIMESTAMP_WITH_TIME_ZONE"
        }
    )
    void shouldParseAddedColumnTypes(String sqlType, ColumnType expectedType) {
        String sql = """
            CREATE TABLE users (
                value %s
            );
            """
            .formatted(sqlType);

        Schema schema = parser.parse(sql);

        assertEquals(
            expectedType,
            schema.tables().getFirst().columns().getFirst().type()
        );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER, INTEGER",
            "INT, INTEGER",
            "BIGINT, BIGINT",
            "SMALLINT, SMALLINT",
            "BOOLEAN, BOOLEAN",
            "BOOL, BOOLEAN",
            "VARCHAR(255), VARCHAR",
            "TEXT, TEXT",
            "DATE, DATE",
            "TIMESTAMP, TIMESTAMP",
            "'DECIMAL(10, 2)', DECIMAL",
            "NUMERIC, DECIMAL"
        }
    )
    void shouldKeepParsingDeliveredColumnTypes(String sqlType, ColumnType expectedType) {
        String sql = """
            CREATE TABLE users (
                value %s
            );
            """
            .formatted(sqlType);

        Schema schema = parser.parse(sql);

        assertEquals(
            expectedType,
            schema.tables().getFirst().columns().getFirst().type()
        );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "TIMESTAMPTZ, TIMESTAMP_WITH_TIME_ZONE",
            "timestamptz, TIMESTAMP_WITH_TIME_ZONE",
            "TIMESTAMPTZ(3), TIMESTAMP_WITH_TIME_ZONE",
            "TIMESTAMP WITHOUT TIME ZONE, TIMESTAMP",
            "timestamp without time zone, TIMESTAMP",
            "TIMESTAMP(3) WITHOUT TIME ZONE, TIMESTAMP",
            "INT2, SMALLINT",
            "int2, SMALLINT",
            "INT4, INTEGER",
            "int4, INTEGER",
            "INT8, BIGINT",
            "int8, BIGINT",
            "SMALLSERIAL, SMALLINT",
            "smallserial, SMALLINT",
            "SERIAL2, SMALLINT",
            "serial2, SMALLINT",
            "SERIAL4, INTEGER",
            "serial4, INTEGER",
            "SERIAL8, BIGINT",
            "serial8, BIGINT",
            "CHARACTER VARYING, VARCHAR",
            "character varying, VARCHAR",
            "CHARACTER VARYING(20), VARCHAR",
            "CHAR, VARCHAR",
            "char, VARCHAR",
            "CHAR(2), VARCHAR",
            "CHARACTER, VARCHAR",
            "character(3), VARCHAR",
            "REAL, REAL",
            "real, REAL",
            "FLOAT4, REAL",
            "float4, REAL",
            "DOUBLE PRECISION, DOUBLE_PRECISION",
            "double precision, DOUBLE_PRECISION",
            "FLOAT8, DOUBLE_PRECISION",
            "float8, DOUBLE_PRECISION",
            "BYTEA, BYTEA",
            "bytea, BYTEA",
            "TIME, TIME",
            "time, TIME",
            "TIME(3), TIME",
            "TIME WITHOUT TIME ZONE, TIME",
            "time without time zone, TIME",
            "TIME(3) WITHOUT TIME ZONE, TIME"
        }
    )
    void shouldParsePostgresTypeSpellings(String sqlType, ColumnType expectedType) {
        String sql = """
            CREATE TABLE users (
                value %s
            );
            """
            .formatted(sqlType);

        Schema schema = parser.parse(sql);

        assertEquals(
            expectedType,
            schema.tables().getFirst().columns().getFirst().type()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "SERIAL",
            "BIGSERIAL",
            "SMALLSERIAL",
            "SERIAL2",
            "SERIAL4",
            "SERIAL8"
        }
    )
    void shouldParseSerialColumnAsNotNullable(String sqlType) {
        String sql = """
            CREATE TABLE users (
                id %s PRIMARY KEY
            );
            """
            .formatted(sqlType);

        Schema schema = parser.parse(sql);

        assertFalse(schema.tables().getFirst().columns().getFirst().nullable());
    }

    @Test
    void shouldParseNullabilityOfAddedColumnTypes() {
        String sql = """
            CREATE TABLE users (
                external_id UUID,
                created_at  TIMESTAMP WITH TIME ZONE NOT NULL
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            new Column("external_id", ColumnType.UUID, true),
            table.columns().get(0)
        );

        assertEquals(
            new Column("created_at", ColumnType.TIMESTAMP_WITH_TIME_ZONE, false),
            table.columns().get(1)
        );
    }

    /**
     * The integer aliases carry no implicit {@code NOT NULL}, unlike the serial
     * spellings, so their nullability comes from {@code NOT NULL} only.
     */
    @Test
    void shouldParseNullabilityOfIntegerAliasColumns() {
        String sql = """
            CREATE TABLE users (
                score    INT2,
                code     INT4,
                revision INT8
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            List.of(
                new Column("score", ColumnType.SMALLINT, true),
                new Column("code", ColumnType.INTEGER, true),
                new Column("revision", ColumnType.BIGINT, true)
            ),
            table.columns()
        );
    }

    /**
     * A column of a type sqlcj cannot map, including every array column, is
     * recorded with its declared type text instead of failing the schema, so
     * only a query that uses the column fails. The recorded text is the
     * canonical spelling of the declared type, followed by {@code []} per
     * declared array dimension.
     *
     * <p>{@code FLOAT} and {@code FLOAT(p)}, whose precision selects the type,
     * and the time-zone-aware time spellings are unmapped beside the mapped
     * floating-point, binary, and time spellings.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "JSONB, JSONB",
            "FLOAT, FLOAT",
            "float(24), FLOAT",
            "TIMETZ, TIMETZ",
            "time with time zone, TIME WITH TIME ZONE",
            "real[], REAL[]",
            "\"char\", \"CHAR\"",
            "varchar(20)[], VARCHAR[]",
            "integer[][], INTEGER[][]",
            "'numeric(10, 2)[3]', NUMERIC[]"
        }
    )
    void shouldRecordUnsupportedColumnType(String sqlType, String recordedType) {
        String sql = """
            CREATE TABLE users (
                id    BIGINT NOT NULL,
                value %s
            );
            """
            .formatted(sqlType);

        Table table = parser.parse(sql).tables().getFirst();

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("value", null, true, recordedType)
            ),
            table.columns()
        );
    }

    /**
     * A recorded column's nullability is parsed from {@code NOT NULL} like any
     * other column's.
     */
    @Test
    void shouldParseNullabilityOfUnsupportedColumnTypes() {
        String sql = """
            CREATE TABLE users (
                tags     INT[] NOT NULL,
                metadata JSONB
            );
            """;

        Table table = parser.parse(sql).tables().getFirst();

        assertEquals(
            List.of(
                new Column("tags", null, false, "INT[]"),
                new Column("metadata", null, true, "JSONB")
            ),
            table.columns()
        );
    }

    @Test
    void shouldParseColumnSpecificationsThatDoNotAffectTypes() {
        String sql = """
            CREATE TABLE orders (
                id         BIGINT NOT NULL PRIMARY KEY,
                quantity   INTEGER NOT NULL DEFAULT 0 CHECK (quantity > 0),
                status     VARCHAR(32) DEFAULT 'new',
                created_at TIMESTAMP DEFAULT now(),
                user_id    BIGINT REFERENCES users (id),
                owner_id   BIGINT REFERENCES users (id) ON DELETE CASCADE
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("quantity", ColumnType.INTEGER, false),
                new Column("status", ColumnType.VARCHAR, true),
                new Column("created_at", ColumnType.TIMESTAMP, true),
                new Column("user_id", ColumnType.BIGINT, true),
                new Column("owner_id", ColumnType.BIGINT, true)
            ),
            table.columns()
        );

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("id")
                )
            ),
            table.constraints()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "FOREIGN KEY (user_id) REFERENCES users (id)",
            "CONSTRAINT orders_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE",
            "CHECK (quantity > 0)",
            "CONSTRAINT orders_quantity_check CHECK (quantity > 0)"
        }
    )
    void shouldIgnoreForeignKeyAndCheckTableConstraints(String tableConstraint) {
        String sql = """
            CREATE TABLE orders (
                id       BIGINT NOT NULL,
                user_id  BIGINT NOT NULL,
                quantity INTEGER NOT NULL,
                %s
            );
            """
            .formatted(tableConstraint);

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("user_id", ColumnType.BIGINT, false),
                new Column("quantity", ColumnType.INTEGER, false)
            ),
            table.columns()
        );

        assertTrue(table.constraints().isEmpty());
    }

    @Test
    void shouldParseNamedTableConstraintsAlongsideIgnoredOnes() {
        String sql = """
            CREATE TABLE orders (
                id       BIGINT NOT NULL,
                code     VARCHAR(32) NOT NULL,
                user_id  BIGINT NOT NULL,
                quantity INTEGER NOT NULL DEFAULT 1,
                CONSTRAINT orders_pk PRIMARY KEY (id),
                CONSTRAINT orders_user_fk FOREIGN KEY (user_id) REFERENCES users (id),
                CONSTRAINT orders_quantity_check CHECK (quantity > 0),
                CONSTRAINT orders_code_unique UNIQUE (code),
                FOREIGN KEY (code) REFERENCES codes (code),
                CHECK (quantity < 100)
            );
            """;

        Schema schema = parser.parse(sql);

        Table table = schema.tables().getFirst();

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("id")
                ),
                new Constraint(
                    ConstraintType.UNIQUE,
                    List.of("code")
                )
            ),
            table.constraints()
        );
    }

    @Test
    void shouldRejectUnsupportedSchemaStatement() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL
            );

            CREATE VIEW active_users AS SELECT id FROM users;
            """;

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unsupported schema statement: CreateView at line 5",
            exception.getMessage()
        );
    }

    /**
     * An {@code ALTER TABLE} action outside the supported forms, such as
     * {@code SET DEFAULT}, is rejected like any other unsupported statement.
     */
    @Test
    void shouldRejectUnsupportedAlterTableAction() {
        Schema schema = parser.parse(BASE_SCHEMA);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(schema, "ALTER TABLE users ALTER COLUMN name SET DEFAULT 'new';")
        );

        assertEquals(
            "Unsupported schema statement: Alter at line 1",
            exception.getMessage()
        );
    }

    /**
     * An {@code ALTER COLUMN} action that states column storage instead of a new
     * type is rejected rather than retyping the column.
     */
    @Test
    void shouldRejectAlterColumnSetStatistics() {
        Schema schema = parser.parse(BASE_SCHEMA);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(schema, "ALTER TABLE users ALTER COLUMN name SET STATISTICS 100;")
        );

        assertEquals(
            "Unsupported schema statement: Alter at line 1",
            exception.getMessage()
        );
    }

    /**
     * An {@code ALTER COLUMN} identity action, which the parser reports without
     * any declared type, is rejected rather than failing on its missing type.
     */
    @Test
    void shouldRejectAlterColumnAddIdentity() {
        Schema schema = parser.parse(BASE_SCHEMA);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(
                schema,
                "ALTER TABLE users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY;"
            )
        );

        assertEquals(
            "Unsupported schema statement: Alter at line 1",
            exception.getMessage()
        );
    }

    /**
     * Every statement of the documented ignored list is accepted and leaves the
     * schema the statements before it left.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "CREATE INDEX users_email_idx ON users (email);",
            "CREATE UNIQUE INDEX users_email_idx ON users (email);",
            "ALTER INDEX users_email_idx RENAME TO users_mail_idx;",
            "alter index users_email_idx rename to users_mail_idx;",
            "DROP INDEX users_email_idx;",
            "DROP INDEX IF EXISTS users_email_idx;",
            "COMMENT ON TABLE users IS 'the users';",
            "COMMENT ON COLUMN users.email IS 'the email';",
            "COMMENT ON VIEW active_users IS 'the active users';",
            "CREATE EXTENSION pgcrypto;",
            "CREATE EXTENSION IF NOT EXISTS pgcrypto;",
            "CREATE SEQUENCE users_id_seq;",
            "ALTER SEQUENCE users_id_seq RESTART WITH 1;",
            "DROP SEQUENCE users_id_seq;",
            "GRANT SELECT ON users TO readonly;",
            "REVOKE SELECT ON users FROM readonly;",
            "CREATE OR REPLACE FUNCTION touch() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ "
                + "LANGUAGE plpgsql;",
            "DROP FUNCTION touch();",
            "DROP FUNCTION IF EXISTS touch();",
            "CREATE TRIGGER users_touch BEFORE UPDATE ON users FOR EACH ROW "
                + "EXECUTE FUNCTION touch();",
            "DROP TRIGGER users_touch ON users;",
            "INSERT INTO users (id) VALUES (1);",
            "UPDATE users SET name = 'new';",
            "DELETE FROM users;",
            "CREATE TYPE status AS ENUM ('draft', 'sent');"
        }
    )
    void shouldIgnoreDocumentedStatements(String statement) {
        assertEquals(parser.parse(BASE_SCHEMA).tables(), applied(statement).tables());
    }

    /**
     * An ignored statement is not resolved against the schema, so it names a
     * table or a column the schema does not model without failing.
     */
    @Test
    void shouldNotResolveAnIgnoredStatementAgainstTheSchema() {
        Schema schema = applied("""
            CREATE INDEX payments_total_idx ON payments (total);
            COMMENT ON COLUMN users.nickname IS 'the nickname';
            INSERT INTO payments (total) VALUES (1);
            """);

        assertEquals(parser.parse(BASE_SCHEMA).tables(), schema.tables());
    }

    /**
     * An {@code ALTER TABLE} action that states a constraint is accepted and
     * leaves the table unchanged, named or unnamed.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (id);",
            "ALTER TABLE users ADD PRIMARY KEY (id);",
            "ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);",
            "ALTER TABLE users ADD UNIQUE (email);",
            "ALTER TABLE orders ADD CONSTRAINT orders_user_fk FOREIGN KEY (user_id) "
                + "REFERENCES users (id);",
            "ALTER TABLE orders ADD FOREIGN KEY (user_id) REFERENCES users (id);",
            "ALTER TABLE users ADD CONSTRAINT users_id_check CHECK (id > 0);",
            "ALTER TABLE users DROP CONSTRAINT users_email_key;",
            "ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;",
            "ALTER TABLE users RENAME CONSTRAINT users_email_key TO users_mail_key;"
        }
    )
    void shouldIgnoreConstraintAlterTableActions(String statement) {
        assertEquals(parser.parse(BASE_SCHEMA).tables(), applied(statement).tables());
    }

    /**
     * An ignored constraint action still resolves its table, so an
     * {@code ALTER TABLE} of a table the schema does not model fails.
     */
    @Test
    void shouldReportTheMissingTableOfAnIgnoredConstraintAction() {
        assertEquals(
            "Table not found in schema: payments",
            failureMessage("ALTER TABLE payments ADD CONSTRAINT payments_pkey PRIMARY KEY (id);")
        );
    }

    /**
     * A modeled action beside an ignored constraint action of one
     * {@code ALTER TABLE} is still applied.
     */
    @Test
    void shouldApplyAModeledActionBesideAnIgnoredConstraintAction() {
        Schema schema = applied(
            "ALTER TABLE users ADD COLUMN age INTEGER, "
                + "ADD CONSTRAINT users_age_check CHECK (age > 0);"
        );

        Table users = table(schema, "users");

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("email", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, true),
                new Column("age", ColumnType.INTEGER, true)
            ),
            users.columns()
        );

        assertEquals(
            List.of(new Constraint(ConstraintType.UNIQUE, List.of("email"))),
            users.constraints()
        );
    }

    /**
     * A statement outside the ignored list is rejected naming its kind and the
     * line it begins on.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "CREATE VIEW active_users AS SELECT id FROM users;|CreateView",
            "CREATE TYPE address AS (street TEXT, city TEXT);|CreateType",
            "ALTER TYPE status ADD VALUE 'archived';|AlterType",
            "CREATE DOMAIN positive AS INTEGER CHECK (VALUE > 0);|CreateDomain",
            "CREATE SCHEMA app;|CreateSchema",
            "DROP VIEW active_users;|Drop",
            "SELECT id FROM users;|PlainSelect",
            "ALTER TABLE users ALTER COLUMN name SET DEFAULT 'new';|Alter",
            "CREATE FUNCTION one() RETURNS integer AS 'SELECT 1' LANGUAGE sql;|CreateFunction",
            "ALTER FUNCTION touch() RENAME TO touched;|UnsupportedStatement"
        }
    )
    void shouldRejectStatementOutsideTheIgnoredList(String statement, String kind) {
        Schema schema = parser.parse(BASE_SCHEMA);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(schema, statement)
        );

        assertEquals(
            "Unsupported schema statement: %s at line 1".formatted(kind),
            exception.getMessage()
        );
    }

    /**
     * A single-quoted function body captures the rest of the source, including a
     * later dollar-quoted body, so the function is still rejected at the line it
     * begins on and nothing after it is applied.
     */
    @Test
    void shouldRejectASingleQuotedFunctionBodyThatCapturesALaterDollarQuotedBody() {
        String sql = """
            CREATE TABLE users (id BIGINT);
            CREATE FUNCTION one() RETURNS integer AS 'SELECT 1' LANGUAGE sql;
            CREATE TABLE later (id BIGINT);
            DROP TABLE users;
            CREATE FUNCTION touch() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql;
            """;

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unsupported schema statement: CreateFunction at line 2",
            exception.getMessage()
        );
    }

    /**
     * The reported line is the line the rejected statement itself begins on,
     * even after comments that contain a statement separator and after a
     * multi-line dollar-quoted function body.
     */
    @Test
    void shouldReportTheLineOfARejectedStatementAfterCommentsAndAFunctionBody() {
        String sql = """
            -- a line comment with a ; separator
            /* a block comment
               with a ; separator */
            CREATE TABLE tags (
                id BIGINT NOT NULL
            );

            CREATE OR REPLACE FUNCTION touch() RETURNS trigger AS $$
            BEGIN
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;

            -- another ; separator
            CREATE VIEW active_tags AS SELECT id FROM tags;
            """;

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unsupported schema statement: CreateView at line 15",
            exception.getMessage()
        );
    }

    /**
     * Each source is applied to the schema the previous ones left, and leaves
     * their schema untouched.
     */
    @Test
    void shouldComposeTheSchemaOfSeveralParsedSources() {
        Schema first = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL
            );
            """);

        Schema second = parser.parse(
            first,
            "ALTER TABLE users ADD COLUMN name VARCHAR(255);"
        );

        Schema third = parser.parse(second, "ALTER TABLE users RENAME TO people;");

        assertEquals(List.of("people"), tableNames(third));

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("name", ColumnType.VARCHAR, true)
            ),
            third.tables().getFirst().columns()
        );

        assertEquals(List.of("users"), tableNames(first));

        assertEquals(
            List.of(new Column("id", ColumnType.BIGINT, false)),
            first.tables().getFirst().columns()
        );
    }

    @Test
    void shouldAppendAddedColumnsTypedLikeCreateTableColumns() {
        Schema schema = applied("""
            ALTER TABLE users ADD COLUMN created_at TIMESTAMPTZ NOT NULL;
            ALTER TABLE users ADD COLUMN revision SERIAL;
            ALTER TABLE users ADD COLUMN metadata JSONB;
            ALTER TABLE users ADD COLUMN tags varchar(20)[];
            """);

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("email", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, true),
                new Column("created_at", ColumnType.TIMESTAMP_WITH_TIME_ZONE, false),
                new Column("revision", ColumnType.INTEGER, false),
                new Column("metadata", null, true, "JSONB"),
                new Column("tags", null, true, "VARCHAR[]")
            ),
            table(schema, "users").columns()
        );
    }

    @Test
    void shouldRecordTheColumnConstraintsOfAnAddedColumn() {
        Schema schema = applied("""
            ALTER TABLE orders ADD COLUMN code VARCHAR(32) UNIQUE;
            ALTER TABLE orders ADD COLUMN reference BIGINT PRIMARY KEY;
            """);

        assertEquals(
            List.of(
                new Constraint(ConstraintType.UNIQUE, List.of("code")),
                new Constraint(ConstraintType.PRIMARY_KEY, List.of("reference"))
            ),
            table(schema, "orders").constraints()
        );
    }

    @Test
    void shouldDropColumnAndTheConstraintsThatListIt() {
        Schema schema = applied("ALTER TABLE users DROP COLUMN email;");

        Table users = table(schema, "users");

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("name", ColumnType.VARCHAR, true)
            ),
            users.columns()
        );

        assertTrue(users.constraints().isEmpty());
    }

    @Test
    void shouldRenameColumnInItsPositionAndInItsConstraints() {
        Schema schema = applied(
            "ALTER TABLE users RENAME COLUMN email TO \"Email Address\";"
        );

        Table users = table(schema, "users");

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("Email Address", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, true)
            ),
            users.columns()
        );

        assertEquals(
            List.of(
                new Constraint(ConstraintType.UNIQUE, List.of("Email Address"))
            ),
            users.constraints()
        );
    }

    @Test
    void shouldRenameTableInItsPosition() {
        Schema schema = applied("ALTER TABLE users RENAME TO \"people\";");

        assertEquals(List.of("people", "orders"), tableNames(schema));

        Table people = table(schema, "people");

        assertEquals(3, people.columns().size());

        assertEquals(
            List.of(new Constraint(ConstraintType.UNIQUE, List.of("email"))),
            people.constraints()
        );
    }

    /**
     * A type change states no nullability, so the column keeps the nullability
     * it had, whether its new type is mapped or recorded.
     */
    @Test
    void shouldChangeColumnTypeInItsPositionKeepingItsNullability() {
        Schema schema = applied("""
            ALTER TABLE users ALTER COLUMN id TYPE INT4;
            ALTER TABLE users ALTER COLUMN email TYPE JSONB;
            """);

        assertEquals(
            List.of(
                new Column("id", ColumnType.INTEGER, false),
                new Column("email", null, true, "JSONB"),
                new Column("name", ColumnType.VARCHAR, true)
            ),
            table(schema, "users").columns()
        );
    }

    @Test
    void shouldChangeARecordedColumnTypeBackToAMappedType() {
        Schema schema = applied("""
            ALTER TABLE users ALTER COLUMN name TYPE JSONB;
            ALTER TABLE users ALTER COLUMN name SET NOT NULL;
            ALTER TABLE users ALTER COLUMN name TYPE TEXT;
            """);

        assertEquals(
            new Column("name", ColumnType.TEXT, false),
            table(schema, "users").columns().get(2)
        );
    }

    @Test
    void shouldSetAndDropColumnNullability() {
        Schema schema = applied("""
            ALTER TABLE users ALTER COLUMN name SET NOT NULL;
            ALTER TABLE users ALTER COLUMN id DROP NOT NULL;
            """);

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, true),
                new Column("email", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, false)
            ),
            table(schema, "users").columns()
        );
    }

    @Test
    void shouldApplyTheActionsOfOneAlterTableInOrder() {
        Schema schema = applied("""
            ALTER TABLE users
                DROP COLUMN email,
                ADD COLUMN status VARCHAR(32) NOT NULL,
                ALTER COLUMN name SET NOT NULL,
                ALTER COLUMN id TYPE INT4;
            """);

        Table users = table(schema, "users");

        assertEquals(
            List.of(
                new Column("id", ColumnType.INTEGER, false),
                new Column("name", ColumnType.VARCHAR, false),
                new Column("status", ColumnType.VARCHAR, false)
            ),
            users.columns()
        );

        assertTrue(users.constraints().isEmpty());
    }

    @Test
    void shouldDropEveryTableOfOneDropStatement() {
        Schema schema = applied("DROP TABLE orders, users;");

        assertTrue(schema.tables().isEmpty());
    }

    @Test
    void shouldIgnoreCreateTableIfNotExistsForAnExistingTable() {
        Schema schema = applied("""
            CREATE TABLE IF NOT EXISTS users (
                other BIGINT NOT NULL
            );
            """);

        assertEquals(List.of("users", "orders"), tableNames(schema));

        assertEquals(3, table(schema, "users").columns().size());
    }

    @Test
    void shouldIgnoreAddColumnIfNotExistsForAnExistingColumn() {
        Schema schema = applied("ALTER TABLE users ADD COLUMN IF NOT EXISTS name TEXT;");

        Table users = table(schema, "users");

        assertEquals(3, users.columns().size());

        assertEquals(
            new Column("name", ColumnType.VARCHAR, true),
            users.columns().get(2)
        );
    }

    @Test
    void shouldIgnoreDropTableIfExistsForAMissingTable() {
        Schema schema = applied("DROP TABLE IF EXISTS payments, orders;");

        assertEquals(List.of("users"), tableNames(schema));
    }

    @Test
    void shouldIgnoreAlterTableIfExistsForAMissingTable() {
        Schema schema = applied(
            "ALTER TABLE IF EXISTS payments ADD COLUMN total DECIMAL(10, 2);"
        );

        assertEquals(parser.parse(BASE_SCHEMA).tables(), schema.tables());
    }

    @Test
    void shouldIgnoreDropColumnIfExistsForAMissingColumn() {
        Schema schema = applied("ALTER TABLE users DROP COLUMN IF EXISTS nickname;");

        Table users = table(schema, "users");

        assertEquals(3, users.columns().size());

        assertEquals(
            List.of(new Constraint(ConstraintType.UNIQUE, List.of("email"))),
            users.constraints()
        );
    }

    /**
     * {@code ALTER TABLE IF EXISTS} covers only the table, so a missing column
     * of an existing table still fails.
     */
    @Test
    void shouldReportTheMissingColumnOfAnAlterTableIfExists() {
        assertEquals(
            "Column not found in table users: nickname",
            failureMessage("ALTER TABLE IF EXISTS users DROP COLUMN nickname;")
        );
    }

    @Test
    void shouldMatchTableAndColumnNamesCaseInsensitively() {
        Schema schema = applied("""
            ALTER TABLE USERS ALTER COLUMN NAME SET NOT NULL;
            ALTER TABLE Users RENAME COLUMN Email TO Mail;
            DROP TABLE Orders;
            """);

        assertEquals(List.of("users"), tableNames(schema));

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("Mail", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, false)
            ),
            table(schema, "users").columns()
        );
    }

    @Test
    void shouldReportTheMissingTableOfAStatement() {
        assertEquals(
            "Table not found in schema: payments",
            failureMessage("ALTER TABLE payments ADD COLUMN total DECIMAL(10, 2);")
        );

        assertEquals(
            "Table not found in schema: payments",
            failureMessage("DROP TABLE payments;")
        );
    }

    @Test
    void shouldReportTheMissingColumnOfAnAlterTableAction() {
        assertEquals(
            "Column not found in table users: nickname",
            failureMessage("ALTER TABLE users DROP COLUMN nickname;")
        );

        assertEquals(
            "Column not found in table users: nickname",
            failureMessage("ALTER TABLE users RENAME COLUMN nickname TO handle;")
        );

        assertEquals(
            "Column not found in table users: nickname",
            failureMessage("ALTER TABLE users ALTER COLUMN nickname TYPE TEXT;")
        );

        assertEquals(
            "Column not found in table users: nickname",
            failureMessage("ALTER TABLE users ALTER COLUMN nickname SET NOT NULL;")
        );

        assertEquals(
            "Column not found in table users: nickname",
            failureMessage("ALTER TABLE users ALTER COLUMN nickname DROP NOT NULL;")
        );
    }

    @Test
    void shouldReportAStatementThatRepeatsATableName() {
        assertEquals(
            "Table already exists in schema: users",
            failureMessage("""
                CREATE TABLE users (
                    id BIGINT NOT NULL
                );
                """)
        );

        assertEquals(
            "Table already exists in schema: orders",
            failureMessage("ALTER TABLE users RENAME TO orders;")
        );
    }

    @Test
    void shouldReportAStatementThatRepeatsAColumnName() {
        assertEquals(
            "Column already exists in table users: name",
            failureMessage("ALTER TABLE users ADD COLUMN name TEXT;")
        );

        assertEquals(
            "Column already exists in table users: name",
            failureMessage("ALTER TABLE users RENAME COLUMN email TO name;")
        );

        assertEquals(
            "Column already exists in table people: name",
            failureMessage("""
                CREATE TABLE people (
                    name TEXT,
                    name VARCHAR(255)
                );
                """)
        );
    }

    @Test
    void shouldReportSyntaxFailureWithItsReasonAndLocation() {
        String sql = """
            CREATE TABLE users (
             id BIGINT NOT NULL,
             name VARCHAR(255)
            ;
            """;

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Encountered unexpected token: \";\" at line 4, column 1",
            exception.getMessage()
        );
    }

    /**
     * A syntax failure at the end of input has no token image to quote, so the
     * reason states the end of input and where the input ended.
     */
    @Test
    void shouldReportSyntaxFailureAtEndOfInputWithItsLocation() {
        String sql = """
            CREATE TABLE users (
             id BIGINT NOT NULL,
             name VARCHAR(255)
            """;

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Encountered unexpected end of input at line 3, column 19",
            exception.getMessage()
        );
    }

    /**
     * A lexical failure such as an unterminated string literal reaches the
     * compiler wrapped in exceptions that repeat their cause's class name, so the
     * reason states the lexical wording alone, which already names its location.
     */
    @Test
    void shouldReportLexicalFailureWithoutExceptionClassNames() {
        String sql = """
            CREATE TABLE users (
             id BIGINT NOT NULL,
             name VARCHAR(255) DEFAULT 'x);
            """;

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Lexical error at line 4, column 0."
                + "  Encountered: <EOF> after: \"\\'x);\\n\"",
            exception.getMessage()
        );
        assertFalse(exception.getMessage().contains("net.sf.jsqlparser"));
    }

    /**
     * A single-file schema of the kind published sqlc examples use loads whole:
     * its enum type, indexes, and dollar-quoted function are accepted and
     * ignored, and its enum and array columns are recorded as unsupported.
     */
    @Test
    void shouldLoadASingleFileSchemaWithEnumArrayIndexAndFunctionStatements() {
        Schema schema = parser.parse(SINGLE_FILE_SCHEMA);

        assertEquals(List.of("shelves", "records"), tableNames(schema));

        Table shelves = table(schema, "shelves");

        assertEquals(
            List.of(
                new Column("shelf_id", ColumnType.INTEGER, false),
                new Column("label", ColumnType.TEXT, false)
            ),
            shelves.columns()
        );

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("shelf_id")
                )
            ),
            shelves.constraints()
        );

        Table records = table(schema, "records");

        assertEquals(
            List.of(
                new Column("record_id", ColumnType.INTEGER, false),
                new Column("shelf_id", ColumnType.INTEGER, false),
                new Column("catalog_no", ColumnType.TEXT, false),
                new Column("state", null, false, "SHELF_STATE"),
                new Column(
                    "released_on",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE,
                    false
                ),
                new Column("genres", null, false, "VARCHAR[]")
            ),
            records.columns()
        );

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("record_id")
                ),
                new Constraint(
                    ConstraintType.UNIQUE,
                    List.of("catalog_no")
                )
            ),
            records.constraints()
        );
    }

    /**
     * A migration file of the kind published sqlc examples use fails on its
     * {@code COMMENT ON TYPE} statement, which JSqlParser does not parse, and
     * the failure states the reason and the location of that statement.
     */
    @Test
    void shouldRejectTheCommentOnTypeStatementOfAMigrationFile() {
        Schema schema = parser.parse(REGION_MIGRATION);

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(schema, STAGE_MIGRATION)
        );

        assertEquals(
            "Encountered unexpected token: \"TYPE\" at line 14, column 12",
            exception.getMessage()
        );
    }

    /**
     * Without that one statement the same migration files load in order: the
     * remaining {@code COMMENT ON} targets, the enum type, and the reshaping
     * statements are applied, and the enum, enum-array, and text-array columns
     * are recorded as unsupported.
     */
    @Test
    void shouldLoadTheMigrationFilesInOrderWithoutTheCommentOnTypeStatement() {
        String loadableStageMigration = STAGE_MIGRATION.replace(
            COMMENT_ON_TYPE_STATEMENT,
            ""
        );

        assertNotEquals(STAGE_MIGRATION, loadableStageMigration);

        Schema schema = parser.parse(
            parser.parse(
                parser.parse(REGION_MIGRATION),
                loadableStageMigration
            ),
            RESHAPE_STAGE_MIGRATION
        );

        assertEquals(List.of("region", "stage"), tableNames(schema));

        Table region = table(schema, "region");

        assertEquals(
            List.of(
                new Column("code", ColumnType.TEXT, true),
                new Column("title", ColumnType.TEXT, false)
            ),
            region.columns()
        );

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("code")
                )
            ),
            region.constraints()
        );

        Table stage = table(schema, "stage");

        assertEquals(
            List.of(
                new Column("id", ColumnType.INTEGER, false),
                new Column("handle", ColumnType.TEXT, false),
                new Column("setting", null, false, "STAGE_SETTING"),
                new Column("past_settings", null, true, "STAGE_SETTING[]"),
                new Column("title", ColumnType.VARCHAR, false),
                new Column("region", ColumnType.TEXT, false),
                new Column("keywords", null, true, "TEXT[]"),
                new Column("opened_at", ColumnType.TIMESTAMP, false)
            ),
            stage.columns()
        );

        assertEquals(
            List.of(
                new Constraint(
                    ConstraintType.PRIMARY_KEY,
                    List.of("id")
                )
            ),
            stage.constraints()
        );
    }

    /** The schema {@code statements} leave when applied to the base schema. */
    private Schema applied(String statements) {
        return parser.parse(parser.parse(BASE_SCHEMA), statements);
    }

    /** The message of the failure {@code statements} cause on the base schema. */
    private String failureMessage(String statements) {
        Schema schema = parser.parse(BASE_SCHEMA);

        return assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(schema, statements)
        )
            .getMessage();
    }

    private List<String> tableNames(Schema schema) {
        return schema.tables().stream()
            .map(Table::name)
            .toList();
    }

    private Table table(Schema schema, String tableName) {
        return schema.tables().stream()
            .filter(table -> table.name().equals(tableName))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void shouldThrowSchemaParseExceptionForInvalidSql() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
            """;

        assertThrows(
            SchemaParseException.class,
            () -> parser.parse(sql)
        );
    }
}
