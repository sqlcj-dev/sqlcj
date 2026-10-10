package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Constraint;
import dev.sqlcj.schema.ConstraintType;
import dev.sqlcj.schema.EnumType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * JSqlParser does not parse and which is therefore ignored.
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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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
            schema.tables().get(0).columns().get(0).type()
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
            schema.tables().get(0).columns().get(0).type()
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
            "TIME(3) WITHOUT TIME ZONE, TIME",
            "JSON, JSON",
            "json, JSON",
            "JSONB, JSONB",
            "jsonb, JSONB"
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
            schema.tables().get(0).columns().get(0).type()
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

        assertFalse(schema.tables().get(0).columns().get(0).nullable());
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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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
     * A column of a type sqlcj cannot map is recorded with its declared type
     * text instead of failing the schema, so only a query that uses the column
     * fails. The recorded text is the canonical spelling of the declared type,
     * followed by {@code []} per declared array dimension.
     *
     * <p>{@code FLOAT} and {@code FLOAT(p)}, whose precision selects the type,
     * and the time-zone-aware time spellings are unmapped beside the mapped
     * floating-point, binary, and time spellings. An array of more than one
     * dimension, an array of an unmapped element type, and an array of
     * {@code BYTEA}, {@code JSON}, or {@code JSONB} are recorded as well.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "XML, XML",
            "FLOAT, FLOAT",
            "float(24), FLOAT",
            "TIMETZ, TIMETZ",
            "time with time zone, TIME WITH TIME ZONE",
            "bytea[], BYTEA[]",
            "json[], JSON[]",
            "jsonb[], JSONB[]",
            "xml[], XML[]",
            "\"char\", \"CHAR\"",
            "integer[][], INTEGER[][]",
            "'numeric(10, 2)[3][2]', NUMERIC[][]"
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

        Table table = parser.parse(sql).tables().get(0);

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
                tags     JSONB[] NOT NULL,
                metadata XML
            );
            """;

        Table table = parser.parse(sql).tables().get(0);

        assertEquals(
            List.of(
                new Column("tags", null, false, "JSONB[]"),
                new Column("metadata", null, true, "XML")
            ),
            table.columns()
        );
    }

    /**
     * A column declared with exactly one array dimension is an array of its
     * declared element type, which is any mapped type other than {@code BYTEA},
     * {@code JSON}, and {@code JSONB}. The declared size of a dimension is
     * ignored, as PostgreSQL ignores it, and so are the element type's
     * parenthesized arguments.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INTEGER[], INTEGER",
            "int4[], INTEGER",
            "BIGINT[], BIGINT",
            "SMALLINT[], SMALLINT",
            "BOOLEAN[], BOOLEAN",
            "'VARCHAR(20)[]', VARCHAR",
            "TEXT[], TEXT",
            "DATE[], DATE",
            "TIME[], TIME",
            "TIMESTAMP[], TIMESTAMP",
            "timestamptz[], TIMESTAMP_WITH_TIME_ZONE",
            "'numeric(10, 2)[3]', DECIMAL",
            "REAL[], REAL",
            "DOUBLE PRECISION[], DOUBLE_PRECISION",
            "uuid[], UUID"
        }
    )
    void shouldModelOneDimensionalArrayColumns(String sqlType, ColumnType elementType) {
        String sql = """
            CREATE TABLE users (
                id    BIGINT NOT NULL,
                value %s NOT NULL
            );

            ALTER TABLE users ADD COLUMN added %s;
            ALTER TABLE users ALTER COLUMN id TYPE %s;
            """
            .formatted(sqlType, sqlType, sqlType);

        Table table = parser.parse(sql).tables().get(0);

        assertEquals(
            List.of(
                new Column("id", elementType, false, null, null, true),
                new Column("value", elementType, false, null, null, true),
                new Column("added", elementType, true, null, null, true)
            ),
            table.columns()
        );
    }

    /**
     * An array of the blank-padded {@code CHAR} or {@code CHARACTER} spelling
     * is a {@code VARCHAR} array that carries the spelling, which PostgreSQL
     * names {@code bpchar}, and an array of a varying spelling does not. The
     * spelling is carried through every statement that types a column, and kept
     * through a rename and a nullability change, as the array shape is.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "'CHAR(3)[]', true",
            "'character(3)[]', true",
            "CHARACTER[], true",
            "'VARCHAR(20)[]', false",
            "'character varying(20)[]', false"
        }
    )
    void shouldModelTheBlankPaddedSpellingOfCharacterArrayColumns(
        String sqlType,
        boolean blankPadded
    ) {
        String sql = """
            CREATE TABLE users (
                id    BIGINT NOT NULL,
                value %s NOT NULL
            );

            ALTER TABLE users ADD COLUMN added %s;
            ALTER TABLE users ALTER COLUMN id TYPE %s;
            ALTER TABLE users RENAME COLUMN added TO renamed;
            ALTER TABLE users ALTER COLUMN renamed SET NOT NULL;
            """
            .formatted(sqlType, sqlType, sqlType);

        Table table = parser.parse(sql).tables().get(0);

        assertEquals(
            List.of(
                new Column("id", ColumnType.VARCHAR, false, null, null, true, blankPadded),
                new Column("value", ColumnType.VARCHAR, false, null, null, true, blankPadded),
                new Column("renamed", ColumnType.VARCHAR, false, null, null, true, blankPadded)
            ),
            table.columns()
        );
    }

    /**
     * An array column keeps its element type and its array shape through a
     * rename and through a nullability change, and a type change replaces both.
     */
    @Test
    void shouldCarryTheArrayShapeOfARenamedAndRetypedColumn() {
        Schema schema = parser.parse("""
            CREATE TABLE users (
                tags TEXT[]
            );

            ALTER TABLE users RENAME COLUMN tags TO labels;
            ALTER TABLE users ALTER COLUMN labels SET NOT NULL;
            """);

        assertEquals(
            List.of(new Column("labels", ColumnType.TEXT, false, null, null, true)),
            table(schema, "users").columns()
        );

        assertEquals(
            List.of(new Column("labels", ColumnType.INTEGER, false)),
            table(
                parser.parse(schema, "ALTER TABLE users ALTER COLUMN labels TYPE INTEGER;"),
                "users"
            )
                .columns()
        );

        assertEquals(
            List.of(new Column("labels", null, false, "JSONB[]")),
            table(
                parser.parse(schema, "ALTER TABLE users ALTER COLUMN labels TYPE JSONB[];"),
                "users"
            )
                .columns()
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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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

        Table table = schema.tables().get(0);

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

            ALTER FOREIGN TABLE users ADD COLUMN name TEXT;
            """;

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unsupported schema statement: ALTER FOREIGN TABLE at line 5",
            exception.getMessage()
        );
    }

    /**
     * Every parsed {@code ALTER TABLE} action that cannot change a column's
     * existence, name, type, or nullability is accepted and leaves the tables
     * the statements before it left, whether the parser has a form of its own
     * for the action or reports it only as unspecific text.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALTER TABLE users ALTER COLUMN name SET DEFAULT 'new';",
            "ALTER TABLE users ALTER COLUMN name DROP DEFAULT;",
            "ALTER TABLE users ALTER COLUMN name SET STATISTICS 100;",
            "ALTER TABLE users ALTER COLUMN name SET STORAGE PLAIN;",
            "ALTER TABLE users ALTER COLUMN name SET COMPRESSION pglz;",
            "ALTER TABLE users ALTER COLUMN name DROP EXPRESSION;",
            "ALTER TABLE users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY;",
            "ALTER TABLE users ALTER COLUMN id SET GENERATED BY DEFAULT;",
            "ALTER TABLE users ALTER COLUMN id SET INCREMENT BY 2;",
            "ALTER TABLE users ALTER COLUMN id RESTART;",
            "ALTER TABLE users ALTER COLUMN id DROP IDENTITY;",
            "ALTER TABLE users ALTER COLUMN id DROP IDENTITY IF EXISTS;",
            "ALTER TABLE users ENABLE ROW LEVEL SECURITY;",
            "ALTER TABLE users DISABLE ROW LEVEL SECURITY;",
            "ALTER TABLE users FORCE ROW LEVEL SECURITY;",
            "ALTER TABLE users NO FORCE ROW LEVEL SECURITY;",
            "ALTER TABLE users ATTACH PARTITION users_p1 FOR VALUES FROM (1) TO (2);",
            "ALTER TABLE users DETACH PARTITION users_p1;",
            "ALTER TABLE users OWNER TO app;",
            "ALTER TABLE users ENABLE TRIGGER users_touch;",
            "ALTER TABLE users DISABLE TRIGGER users_touch;",
            "ALTER TABLE users ENABLE REPLICA TRIGGER users_touch;",
            "ALTER TABLE users ENABLE ALWAYS TRIGGER users_touch;",
            "ALTER TABLE users ENABLE RULE users_nodelete;",
            "ALTER TABLE users DISABLE RULE users_nodelete;",
            "ALTER TABLE users ENABLE REPLICA RULE users_nodelete;",
            "ALTER TABLE users ENABLE ALWAYS RULE users_nodelete;",
            "ALTER TABLE users VALIDATE CONSTRAINT users_email_key;",
            "ALTER TABLE users REPLICA IDENTITY FULL;",
            "ALTER TABLE users REPLICA IDENTITY USING INDEX users_email_idx;",
            "ALTER TABLE users CLUSTER ON users_email_idx;",
            "ALTER TABLE users SET WITHOUT CLUSTER;",
            "ALTER TABLE users SET WITHOUT OIDS;",
            "ALTER TABLE users SET (fillfactor = 70);",
            "ALTER TABLE users SET (fillfactor = 70, autovacuum_enabled = false);",
            "ALTER TABLE users RESET (fillfactor);",
            "ALTER TABLE users SET TABLESPACE fast;",
            "ALTER TABLE users SET LOGGED;",
            "ALTER TABLE users SET UNLOGGED;",
            "ALTER TABLE users SET ACCESS METHOD heap;",
            "ALTER TABLE users INHERIT people;",
            "ALTER TABLE users NO INHERIT people;",
            "ALTER TABLE users OF person_type;",
            "ALTER TABLE users NOT OF;",
            "alter table users owner to app;",
            "alter table users alter column name set statistics 100;",
            "ALTER TABLE ONLY public.users OWNER TO app;",
            "ALTER TABLE ONLY public.users ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;",
            "ALTER TABLE users OWNER TO app, VALIDATE CONSTRAINT users_email_key;"
        }
    )
    void shouldIgnoreTheAlterTableActionsThatCannotChangeAColumn(String statement) {
        Schema base = parser.parse(BASE_SCHEMA);
        Schema schema = applied(statement);

        assertEquals(base.tables(), schema.tables());
        assertEquals(base.enums(), schema.enums());
    }

    /**
     * A modeled action beside ignored column and table actions of one
     * {@code ALTER TABLE} is still applied.
     */
    @Test
    void shouldApplyAnAddedColumnBesideIgnoredActions() {
        Schema schema = applied(
            "ALTER TABLE users ADD COLUMN age INTEGER, "
                + "ALTER COLUMN name SET DEFAULT 'new', "
                + "ALTER COLUMN name SET STATISTICS 100, "
                + "OWNER TO app;"
        );

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("email", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, true),
                new Column("age", ColumnType.INTEGER, true)
            ),
            table(schema, "users").columns()
        );
    }

    /**
     * A modeled action written after a table-level action the parser has a form
     * of its own for is applied as well.
     */
    @Test
    void shouldApplyAnAddedColumnAfterARowLevelSecurityAction() {
        Schema schema = applied("ALTER TABLE users ENABLE ROW LEVEL SECURITY, ADD COLUMN x int;");

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("email", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, true),
                new Column("x", ColumnType.INTEGER, true)
            ),
            table(schema, "users").columns()
        );
    }

    /**
     * An ignored action still resolves its table, so an {@code ALTER TABLE} of a
     * table the schema does not model fails, and {@code IF EXISTS} skips it.
     */
    @Test
    void shouldReportTheMissingTableOfAnIgnoredAction() {
        assertEquals(
            "Table not found in schema: payments",
            failureMessage("ALTER TABLE payments OWNER TO app;")
        );

        assertEquals(
            parser.parse(BASE_SCHEMA).tables(),
            applied("ALTER TABLE IF EXISTS payments OWNER TO app;").tables()
        );
    }

    /**
     * An ignored {@code ALTER COLUMN} action does not resolve its column, so it
     * is accepted for a column the schema does not model.
     */
    @Test
    void shouldIgnoreAnAlterColumnActionOnAMissingColumn() {
        assertEquals(
            parser.parse(BASE_SCHEMA).tables(),
            applied("ALTER TABLE users ALTER COLUMN nickname SET STATISTICS 100;").tables()
        );
    }

    /**
     * A table-level {@code EXCLUDE} constraint is accepted and not recorded,
     * named or unnamed, leaving the columns and the constraints beside it
     * modeled.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "CONSTRAINT rooms_during_excl EXCLUDE USING gist (during WITH &&)",
            "EXCLUDE USING gist (during WITH &&)"
        }
    )
    void shouldIgnoreExcludeTableConstraints(String constraint) {
        Schema schema = parser.parse("""
            CREATE TABLE rooms (
                id     BIGINT NOT NULL,
                during tsrange,
                PRIMARY KEY (id),
                %s
            );
            """.formatted(constraint));

        Table rooms = table(schema, "rooms");

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("during", null, true, "TSRANGE")
            ),
            rooms.columns()
        );

        assertEquals(
            List.of(new Constraint(ConstraintType.PRIMARY_KEY, List.of("id"))),
            rooms.constraints()
        );
    }

    /**
     * Every statement outside the forms sqlcj models is accepted and leaves the
     * schema the statements before it left, whatever object it states.
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
            "CREATE VIEW active_users AS SELECT id FROM users;",
            "CREATE OR REPLACE VIEW active_users AS SELECT id FROM users;",
            "DROP VIEW active_users;",
            "CREATE MATERIALIZED VIEW active_users AS SELECT id FROM users;",
            "REFRESH MATERIALIZED VIEW active_users;",
            "DROP MATERIALIZED VIEW active_users;",
            "CREATE SCHEMA app;",
            "DROP SCHEMA app;",
            "SET statement_timeout = 0;",
            "SET search_path = public;",
            "RESET ALL;",
            "TRUNCATE users;",
            "CREATE POLICY users_owner ON users USING (true);",
            "CREATE ROLE readonly;",
            "CREATE USER reader;",
            "ALTER ROLE readonly NOLOGIN;",
            "ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO readonly;",
            "CREATE DOMAIN positive AS INTEGER CHECK (VALUE > 0);",
            "ALTER DOMAIN positive DROP NOT NULL;",
            "CREATE TYPE address AS (street TEXT, city TEXT);",
            "CREATE TYPE bounds AS RANGE (subtype = numeric);",
            "CREATE TYPE opaque_state;",
            "SELECT pg_catalog.set_config('search_path', '', false);",
            "CREATE PROCEDURE touch() AS $$ BEGIN END; $$ LANGUAGE plpgsql;",
            "ANALYZE users;",
            "COMMIT;",
            "ALTER FUNCTION touch() RENAME TO touched;",
            "ALTER SCHEMA app RENAME TO archive;",
            "CREATE AGGREGATE total (INTEGER) (sfunc = add, stype = INTEGER);",
            "CREATE CAST (INTEGER AS TEXT) WITH FUNCTION render(INTEGER);"
        }
    )
    void shouldIgnoreEveryStatementItDoesNotModel(String statement) {
        Schema base = parser.parse(BASE_SCHEMA);
        Schema schema = applied(statement);

        assertEquals(base.tables(), schema.tables());
        assertEquals(base.enums(), schema.enums());
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
            CREATE VIEW payment_totals AS SELECT total FROM payments;
            TRUNCATE payments;
            """);

        assertEquals(parser.parse(BASE_SCHEMA).tables(), schema.tables());
    }

    /**
     * A statement sqlcj's parser cannot read at all is ignored like any parsed
     * statement outside the modeled forms, because it does not open as table or
     * type DDL and therefore cannot change what the schema models.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "DO $$ BEGIN RAISE NOTICE 'x'; END $$;",
            "DO $do$ BEGIN RAISE NOTICE 'x'; END $do$;",
            "DO LANGUAGE plpgsql $$ BEGIN END $$;",
            "DROP TYPE stage_setting;",
            "COMMENT ON TYPE stage_setting IS 'the setting';",
            "COMMENT ON INDEX users_email_idx IS 'the index';",
            "COMMENT ON FUNCTION touch() IS 'the trigger function';",
            "COMMENT ON SCHEMA public IS 'the schema';",
            "COMMENT ON EXTENSION pgcrypto IS 'the extension';",
            "COMMENT ON CONSTRAINT users_email_key ON users IS 'the constraint';",
            "SET search_path TO app, public;",
            "DROP POLICY users_owner ON users;",
            "DROP DOMAIN positive;",
            "VACUUM;",
            "VACUUM ANALYZE users;",
            "LOCK TABLE users IN ACCESS EXCLUSIVE MODE;",
            "BEGIN;",
            "COPY users (id, email) TO stdout;",
            "CREATE RULE users_nodelete AS ON DELETE TO users DO NOTHING;",
            "CREATE EVENT TRIGGER users_ddl ON ddl_command_start EXECUTE FUNCTION touch();",
            "CREATE FUNCTION one() RETURNS integer AS 'SELECT 1' LANGUAGE sql;",
            "CREATE PROCEDURE one() AS 'SELECT 1' LANGUAGE sql;",
            "CREATE FUNCTION two() RETURNS integer LANGUAGE sql RETURN 1;",
            "CREATE FUNCTION touch() RETURNS trigger AS $fn$BEGIN RETURN NEW; END$fn$ "
                + "LANGUAGE plpgsql;"
        }
    )
    void shouldIgnoreEveryUnreadableStatementThatIsNotTableOrTypeDdl(String statement) {
        Schema base = parser.parse(BASE_SCHEMA);
        Schema schema = applied(statement);

        assertEquals(base.tables(), schema.tables());
        assertEquals(base.enums(), schema.enums());
    }

    /**
     * An unreadable {@code ALTER TABLE} whose every top-level action adds a
     * constraint or an identity is ignored without its table being resolved, so
     * it is ignored for a table the schema does not model at all.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALTER TABLE payments ALTER id ADD GENERATED;",
            "ALTER TABLE payments ALTER COLUMN id ADD GENERATED;",
            "ALTER TABLE payments ADD CONSTRAINT payments_excl EXCLUDE USING gist (id WITH =);",
            "ALTER TABLE payments ADD EXCLUDE USING gist (id WITH =);",
            "ALTER TABLE payments ADD CONSTRAINT payments_key UNIQUE (id) "
                + "DEFERRABLE INITIALLY DEFERRED;",
            "ALTER TABLE payments ADD UNIQUE (id) DEFERRABLE INITIALLY DEFERRED;",
            "ALTER TABLE payments ADD CONSTRAINT payments_key UNIQUE USING INDEX payments_idx;",
            "ALTER TABLE payments ADD PRIMARY KEY USING INDEX payments_idx;",
            "ALTER TABLE payments ADD FOREIGN KEY (id) REFERENCES users (id) NOT VALID;",
            "ALTER TABLE payments ADD CONSTRAINT payments_check CHECK (id > 0) NOT VALID;",
            "ALTER TABLE payments ADD CHECK (id > 0) NOT VALID;",
            "ALTER TABLE payments ADD CHECK (id > 0);",
            "alter table payments add check (id > 0) not valid;",
            "ALTER TABLE IF EXISTS ONLY payments ADD CHECK (id > 0);",
            "ALTER TABLE payments ADD CHECK (id > 0), "
                + "ADD CONSTRAINT payments_key UNIQUE (id) DEFERRABLE;"
        }
    )
    void shouldIgnoreTheUnreadableAlterTableActionsThatCannotChangeAColumn(String statement) {
        Schema base = parser.parse(BASE_SCHEMA);
        Schema schema = applied(statement);

        assertEquals(base.tables(), schema.tables());
        assertEquals(base.enums(), schema.enums());
    }

    /**
     * The identity form {@code pg_dump} writes for every identity column spans
     * several lines and names a sequence, and is ignored like the single-line
     * forms.
     */
    @Test
    void shouldIgnoreThePgDumpIdentityAlterTableAction() {
        Schema base = parser.parse(BASE_SCHEMA);

        Schema schema = applied("""
            ALTER TABLE ONLY public.payments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
                SEQUENCE NAME public.payments_id_seq
                START WITH 1
                INCREMENT BY 1
                NO MINVALUE
                NO MAXVALUE
                CACHE 1
            );
            """);

        assertEquals(base.tables(), schema.tables());
        assertEquals(base.enums(), schema.enums());
    }

    /**
     * A {@code CREATE TYPE} that is not an enum is ignored rather than modeled,
     * so a column of that type is recorded with the canonical spelling of its
     * declared type, like a column of any type sqlcj cannot map.
     */
    @Test
    void shouldRecordAColumnOfAnIgnoredCompositeType() {
        Schema schema = parser.parse("""
            CREATE TYPE address AS (street TEXT, city TEXT);

            CREATE TABLE places (
                id       BIGINT NOT NULL,
                location address
            );
            """);

        assertTrue(schema.enums().isEmpty());

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("location", null, true, "ADDRESS")
            ),
            table(schema, "places").columns()
        );
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
     * A statement sqlcj rejects is reported in SQL terms, naming the statement
     * or the action and the line it begins on, never a parser class.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "ALTER TABLE users SET SCHEMA archive;"
                + "|Unsupported ALTER TABLE action: SET SCHEMA archive at line 1",
            "ALTER TABLE users ADD COLUMN x int, SET SCHEMA archive;"
                + "|Unsupported ALTER TABLE action: SET SCHEMA archive at line 1",
            "ALTER TABLE users OWNER TO app, ADD COLUMN x int;"
                + "|Unsupported ALTER TABLE action: ADD COLUMN x int at line 1",
            "ALTER TABLE users ALTER COLUMN name SET VISIBLE;"
                + "|Unsupported ALTER TABLE action: ALTER COLUMN name SET VISIBLE at line 1",
            "ALTER TABLE users ALTER COLUMN name SET FOO 1;"
                + "|Unsupported ALTER TABLE action: ALTER COLUMN name SET FOO 1 at line 1",
            "ALTER TABLE users ALTER COLUMN name text;"
                + "|Unsupported ALTER TABLE action: ALTER COLUMN name text at line 1",
            "ALTER FOREIGN TABLE ft ADD COLUMN x integer;"
                + "|Unsupported schema statement: ALTER FOREIGN TABLE at line 1",
            "alter foreign table ft add column x integer;"
                + "|Unsupported schema statement: ALTER FOREIGN TABLE at line 1"
        }
    )
    void shouldRejectUnsupportedStatementsInSqlTerms(String statement, String message) {
        Schema schema = parser.parse(BASE_SCHEMA);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(schema, statement)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A rejected {@code ALTER TABLE} action is reported at the line its own
     * statement begins on, after the ignored statements before it.
     */
    @Test
    void shouldReportTheLineOfARejectedAlterTableAction() {
        Schema schema = parser.parse(BASE_SCHEMA);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(schema, """
                ALTER TABLE users OWNER TO app;

                ALTER TABLE users SET SCHEMA archive;
                """)
        );

        assertEquals(
            "Unsupported ALTER TABLE action: SET SCHEMA archive at line 3",
            exception.getMessage()
        );
    }

    /**
     * A function body that is not dollar-quoted ends at its own separator, so it
     * is ignored like any other function and the statements after it, including a
     * later dollar-quoted body, are applied.
     */
    @Test
    void shouldApplyTheStatementsAfterAFunctionBodyThatIsNotDollarQuoted() {
        Schema schema = parser.parse("""
            CREATE TABLE users (id BIGINT);
            CREATE FUNCTION one() RETURNS integer AS 'SELECT 1' LANGUAGE sql;
            CREATE TABLE later (id BIGINT);
            DROP TABLE users;
            CREATE FUNCTION touch() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql;
            CREATE TABLE last (id BIGINT);
            """);

        assertEquals(List.of("later", "last"), tableNames(schema));
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
            ALTER FOREIGN TABLE tags ADD COLUMN label TEXT;
            """;

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unsupported schema statement: ALTER FOREIGN TABLE at line 15",
            exception.getMessage()
        );
    }

    /**
     * An unreadable {@code ALTER TABLE} action outside the ignored ones is
     * rejected with the parser's own reason at the line and column the
     * unexpected token stands at in the file.
     */
    @Test
    void shouldRejectAnUnreadableAlterColumnActionAtItsFileLineAndColumn() {
        Schema schema = parser.parse(BASE_SCHEMA);

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(schema, """
                CREATE INDEX users_email_idx ON users (email);

                ALTER TABLE users ALTER COLUMN name SET DATA TYPE text;
                """)
        );

        assertEquals(
            "Encountered unexpected token: \"DATA\" at line 3, column 41",
            exception.getMessage()
        );
    }

    /**
     * An ignored action beside an unreadable one does not make the statement
     * ignored, and a statement that begins in the middle of a line is reported
     * at the column it begins at.
     */
    @Test
    void shouldRejectAnUnreadableAlterTableActionBesideAnIgnoredOne() {
        Schema schema = parser.parse(BASE_SCHEMA);

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(
                schema,
                "CREATE INDEX users_email_idx ON users (email); "
                    + "ALTER TABLE users ALTER COLUMN name SET DATA TYPE text, "
                    + "ADD CHECK (id > 0);\n"
            )
        );

        assertEquals(
            "Encountered unexpected token: \"DATA\" at line 1, column 88",
            exception.getMessage()
        );
    }

    /** An unreadable {@code CREATE TYPE} is rejected at its file position too. */
    @Test
    void shouldRejectAnUnreadableCreateTypeAtItsFileLineAndColumn() {
        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse("""
                CREATE TABLE payments (id BIGINT NOT NULL);

                CREATE TYPE pair AS (a int, b);
                """)
        );

        assertEquals(
            "Encountered unexpected token: \")\" at line 3, column 30",
            exception.getMessage()
        );
    }

    /**
     * Each statement is parsed alone, so the statements before and after an
     * unreadable one are applied. A dollar-quoted body is kept whole whether its
     * delimiter is tagged or not, and whether the tag is glued to the body or
     * not, so DDL the body states is not modeled and a {@code DROP TABLE} it
     * states drops nothing.
     */
    @Test
    void shouldApplyTheStatementsAroundUnreadableOnesAndNotModelDdlInsideABody() {
        Schema schema = parser.parse("""
            CREATE TABLE users (id BIGINT NOT NULL);
            DO $$ BEGIN RAISE NOTICE 'start'; END $$;
            DO $do$ BEGIN RAISE NOTICE 'tagged'; END $do$;
            CREATE FUNCTION touch() RETURNS trigger AS $fn$BEGIN
                CREATE TABLE glued_inner (id BIGINT);
            END$fn$ LANGUAGE plpgsql;
            CREATE FUNCTION bump() RETURNS integer AS $body$
            BEGIN
                CREATE TABLE tagged_inner (id BIGINT);
                DROP TABLE users;
                RETURN 1;
            END
            $body$ LANGUAGE plpgsql;
            COMMENT ON TYPE stage_setting IS 'ignored';
            CREATE TABLE orders (id BIGINT NOT NULL);
            """);

        assertEquals(List.of("users", "orders"), tableNames(schema));
    }

    /**
     * A rejected statement keeps its own line after a blanked psql meta-command
     * line, unreadable statements, and a multi-line tagged body, each of which
     * the schema source keeps its line numbering through.
     */
    @Test
    void shouldReportTheLineOfARejectedStatementAfterUnreadableOnesAndATaggedBody() {
        String sql = """
            \\restrict aBcD1234
            CREATE TABLE users (id BIGINT NOT NULL);
            DO $$ BEGIN RAISE NOTICE 'start'; END $$;
            CREATE FUNCTION bump() RETURNS integer AS $body$
            BEGIN
                CREATE TABLE tagged_inner (id BIGINT);
                RETURN 1;
            END
            $body$ LANGUAGE plpgsql;
            COMMENT ON TYPE stage_setting IS 'ignored';
            ALTER FOREIGN TABLE users ADD COLUMN label TEXT;
            """;

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unsupported schema statement: ALTER FOREIGN TABLE at line 11",
            exception.getMessage()
        );
    }

    /**
     * A psql meta-command line, which recent {@code pg_dump} releases write
     * around the dumped statements, is skipped rather than lexed, because its
     * first character is a lexical error.
     */
    @Test
    void shouldSkipPsqlMetaCommandLines() {
        Schema schema = parser.parse("""
            \\restrict aBcD1234

            CREATE TABLE users (
                id BIGINT NOT NULL
            );

            \\unrestrict aBcD1234
            """);

        assertEquals(List.of("users"), tableNames(schema));

        assertEquals(
            List.of(new Column("id", ColumnType.BIGINT, false)),
            table(schema, "users").columns()
        );
    }

    /**
     * A tagged dollar-quoted body that is never closed fails the schema source
     * at the delimiter that opened it, because everything after it would belong
     * to that body.
     */
    @Test
    void shouldRejectAnUnterminatedDollarQuotedBody() {
        String sql = """
            CREATE TABLE users (id BIGINT NOT NULL);
            CREATE FUNCTION bump() RETURNS integer AS $body$
            BEGIN
                RETURN 1;
            END;
            """;

        SchemaParseException exception = assertThrows(
            SchemaParseException.class,
            () -> parser.parse(sql)
        );

        assertEquals(
            "Unterminated dollar-quoted string at line 2, column 43",
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
            third.tables().get(0).columns()
        );

        assertEquals(List.of("users"), tableNames(first));

        assertEquals(
            List.of(new Column("id", ColumnType.BIGINT, false)),
            first.tables().get(0).columns()
        );
    }

    @Test
    void shouldAppendAddedColumnsTypedLikeCreateTableColumns() {
        Schema schema = applied("""
            ALTER TABLE users ADD COLUMN created_at TIMESTAMPTZ NOT NULL;
            ALTER TABLE users ADD COLUMN revision SERIAL;
            ALTER TABLE users ADD COLUMN metadata XML;
            ALTER TABLE users ADD COLUMN tags varchar(20)[];
            """);

        assertEquals(
            List.of(
                new Column("id", ColumnType.BIGINT, false),
                new Column("email", ColumnType.VARCHAR, true),
                new Column("name", ColumnType.VARCHAR, true),
                new Column("created_at", ColumnType.TIMESTAMP_WITH_TIME_ZONE, false),
                new Column("revision", ColumnType.INTEGER, false),
                new Column("metadata", null, true, "XML"),
                new Column("tags", ColumnType.VARCHAR, true, null, null, true)
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
            ALTER TABLE users ALTER COLUMN email TYPE XML;
            """);

        assertEquals(
            List.of(
                new Column("id", ColumnType.INTEGER, false),
                new Column("email", null, true, "XML"),
                new Column("name", ColumnType.VARCHAR, true)
            ),
            table(schema, "users").columns()
        );
    }

    @Test
    void shouldChangeARecordedColumnTypeBackToAMappedType() {
        Schema schema = applied("""
            ALTER TABLE users ALTER COLUMN name TYPE XML;
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
     * its indexes and dollar-quoted function are accepted and ignored, its enum
     * type is modeled together with the column that names it, and its array
     * column is recorded as unsupported.
     */
    @Test
    void shouldLoadASingleFileSchemaWithEnumArrayIndexAndFunctionStatements() {
        Schema schema = parser.parse(SINGLE_FILE_SCHEMA);

        assertEquals(List.of("shelves", "records"), tableNames(schema));

        assertEquals(
            List.of(
                new EnumType(
                    "shelf_state",
                    List.of("stocked", "reserved", "retired")
                )
            ),
            schema.enums()
        );

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
                new Column("state", ColumnType.ENUM, false, null, "shelf_state"),
                new Column(
                    "released_on",
                    ColumnType.TIMESTAMP_WITH_TIME_ZONE,
                    false
                ),
                new Column("genres", ColumnType.VARCHAR, false, null, null, true)
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
     * Migration files of the kind published sqlc examples use load in order and
     * unchanged: the {@code COMMENT ON TYPE} statement JSqlParser cannot read is
     * ignored beside the targets it reads, the enum type and the reshaping
     * statements are applied, the enum column is modeled from the declared type,
     * and the enum-array and text-array columns are recorded as unsupported.
     */
    @Test
    void shouldLoadTheMigrationFilesInOrder() {
        Schema schema = parser.parse(
            parser.parse(
                parser.parse(REGION_MIGRATION),
                STAGE_MIGRATION
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
                new Column("setting", ColumnType.ENUM, false, null, "stage_setting"),
                new Column("past_settings", ColumnType.ENUM, true, null, "stage_setting", true),
                new Column("title", ColumnType.VARCHAR, false),
                new Column("region", ColumnType.TEXT, false),
                new Column("keywords", ColumnType.TEXT, true, null, null, true),
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

    /**
     * {@code CREATE TYPE ... AS ENUM} adds the type with its labels in declared
     * order, and a later source sees it.
     */
    @Test
    void shouldAddEnumTypeWithItsDeclaredLabels() {
        Schema schema = parser.parse(
            parser.parse("CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');"),
            "CREATE TYPE shelf_state AS ENUM ('stocked');"
        );

        assertEquals(
            List.of(
                new EnumType("stage_setting", List.of("indoor", "outdoor")),
                new EnumType("shelf_state", List.of("stocked"))
            ),
            schema.enums()
        );
    }

    /**
     * {@code ALTER TYPE ... ADD VALUE} inserts the label at the end by default
     * and directly before or after the stated neighbor otherwise, so the
     * modeled labels keep PostgreSQL's sort order.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "ALTER TYPE stage_setting ADD VALUE 'hybrid';|indoor,outdoor,hybrid",
            "ALTER TYPE stage_setting ADD VALUE 'hybrid' BEFORE 'indoor';|hybrid,indoor,outdoor",
            "ALTER TYPE stage_setting ADD VALUE 'hybrid' AFTER 'indoor';|indoor,hybrid,outdoor",
            "ALTER TYPE stage_setting ADD VALUE IF NOT EXISTS 'hybrid';|indoor,outdoor,hybrid"
        }
    )
    void shouldAddEnumLabelInItsStatedPosition(String statement, String labels) {
        Schema schema = parser.parse(enumSchema(), statement);

        assertEquals(
            List.of(new EnumType("stage_setting", List.of(labels.split(",")))),
            schema.enums()
        );
    }

    /**
     * {@code ADD VALUE IF NOT EXISTS} of a label the type already has changes
     * nothing. As PostgreSQL does, the existing label is decided before the
     * neighbor, so a neighbor the type does not have is not resolved at all.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALTER TYPE stage_setting ADD VALUE IF NOT EXISTS 'indoor';",
            "ALTER TYPE stage_setting ADD VALUE IF NOT EXISTS 'indoor' BEFORE 'missing';"
        }
    )
    void shouldIgnoreAddValueIfNotExistsForAnExistingLabel(String statement) {
        Schema schema = parser.parse(enumSchema(), statement);

        assertEquals(
            List.of(new EnumType("stage_setting", List.of("indoor", "outdoor"))),
            schema.enums()
        );
    }

    /** Each enum diagnostic names the type and, where it applies, the label. */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "CREATE TYPE shelf_state AS ENUM ('new', 'new');|Label already exists in type shelf_state: new",
            "ALTER TYPE shelf_state ADD VALUE 'stocked';|Type not found in schema: shelf_state",
            "ALTER TYPE stage_setting ADD VALUE 'indoor';|Label already exists in type stage_setting: indoor",
            "ALTER TYPE stage_setting ADD VALUE 'hybrid' AFTER 'missing';|Label not found in type stage_setting: missing",
            "ALTER TYPE shelf_state RENAME VALUE 'stocked' TO 'kept';"
                + "|Type not found in schema: shelf_state",
            "ALTER TYPE stage_setting RENAME VALUE 'missing' TO 'covered';"
                + "|Label not found in type stage_setting: missing",
            "ALTER TYPE stage_setting RENAME VALUE 'indoor' TO 'outdoor';"
                + "|Label already exists in type stage_setting: outdoor",
            "ALTER TYPE public.stage_setting RENAME TO stage;"
                + "|Type not found in schema: public.stage_setting",
            "ALTER TYPE public.stage_setting SET SCHEMA archive;"
                + "|Type not found in schema: public.stage_setting"
        }
    )
    void shouldReportTheEnumStatementItCannotApply(String statement, String message) {
        Schema schema = enumSchema();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(schema, statement)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A column of a declared enum type is modeled as an enum column carrying
     * the type's declared name, through every path that types a column: a
     * {@code CREATE TABLE} column, an added column, and a changed column type.
     * The type name is matched case-insensitively and without its SQL
     * identifier delimiters, as PostgreSQL resolves it.
     */
    @Test
    void shouldModelColumnsOfADeclaredEnumType() {
        Schema schema = parser.parse(
            enumSchema(),
            """
                CREATE TABLE stages (
                    id      SERIAL PRIMARY KEY,
                    setting STAGE_SETTING NOT NULL,
                    title   text
                );

                ALTER TABLE stages ADD COLUMN backstage "stage_setting";
                ALTER TABLE stages ALTER COLUMN title TYPE stage_setting;
                """
        );

        assertEquals(
            List.of(
                new Column("id", ColumnType.INTEGER, false),
                new Column("setting", ColumnType.ENUM, false, null, "stage_setting"),
                new Column("title", ColumnType.ENUM, true, null, "stage_setting"),
                new Column("backstage", ColumnType.ENUM, true, null, "stage_setting")
            ),
            table(schema, "stages").columns()
        );
    }

    /**
     * An enum column keeps its type through a rename and through a nullability
     * change, which state no type of their own.
     */
    @Test
    void shouldKeepTheEnumTypeOfARenamedAndRetypedColumn() {
        Schema schema = parser.parse(
            enumSchema(),
            """
                CREATE TABLE stages (
                    setting stage_setting
                );

                ALTER TABLE stages RENAME COLUMN setting TO stage_setting;
                ALTER TABLE stages ALTER COLUMN stage_setting SET NOT NULL;
                """
        );

        assertEquals(
            List.of(new Column("stage_setting", ColumnType.ENUM, false, null, "stage_setting")),
            table(schema, "stages").columns()
        );

        Schema nullable = parser.parse(
            schema,
            "ALTER TABLE stages ALTER COLUMN stage_setting DROP NOT NULL;"
        );

        assertEquals(
            List.of(new Column("stage_setting", ColumnType.ENUM, true, null, "stage_setting")),
            table(nullable, "stages").columns()
        );
    }

    /**
     * A label added after a table is created belongs to the one modeled type,
     * so the column declared earlier reads the added label too.
     */
    @Test
    void shouldApplyAnAddedLabelToAnEarlierEnumColumn() {
        Schema schema = parser.parse(
            enumSchema(),
            """
                CREATE TABLE stages (
                    setting stage_setting NOT NULL
                );

                ALTER TYPE stage_setting ADD VALUE 'hybrid' AFTER 'indoor';
                """
        );

        assertEquals(
            List.of(new Column("setting", ColumnType.ENUM, false, null, "stage_setting")),
            table(schema, "stages").columns()
        );

        assertEquals(
            List.of(new EnumType("stage_setting", List.of("indoor", "hybrid", "outdoor"))),
            schema.enums()
        );
    }

    /**
     * A one-dimensional array of a declared enum type is modeled as an array
     * column of that enum type, through every path that types a column. An
     * array of more dimensions and a type the schema does not declare stay
     * recorded with their declared type.
     */
    @Test
    void shouldModelEnumArraysAndRecordUndeclaredTypesAsUnsupported() {
        Schema schema = parser.parse(
            enumSchema(),
            """
                CREATE TABLE stages (
                    past_settings stage_setting[],
                    history       stage_setting[][],
                    state         shelf_state,
                    title         text
                );

                ALTER TABLE stages ADD COLUMN planned "STAGE_SETTING"[];
                ALTER TABLE stages ALTER COLUMN title TYPE stage_setting[];
                """
        );

        assertEquals(
            List.of(
                new Column("past_settings", ColumnType.ENUM, true, null, "stage_setting", true),
                new Column("history", null, true, "STAGE_SETTING[][]"),
                new Column("state", null, true, "SHELF_STATE"),
                new Column("title", ColumnType.ENUM, true, null, "stage_setting", true),
                new Column("planned", ColumnType.ENUM, true, null, "stage_setting", true)
            ),
            table(schema, "stages").columns()
        );
    }

    /**
     * {@code DROP TYPE} is ignored, because sqlcj's parser cannot read it and it
     * does not open as table or type DDL, so a modeled enum survives it and the
     * columns of that type stay modeled.
     */
    @Test
    void shouldIgnoreDropTypeAndKeepTheModeledEnum() {
        Schema schema = parser.parse(
            parser.parse(
                enumSchema(),
                "CREATE TABLE stages (setting stage_setting NOT NULL);"
            ),
            "DROP TYPE stage_setting;"
        );

        assertEquals(
            List.of(new EnumType("stage_setting", List.of("indoor", "outdoor"))),
            schema.enums()
        );

        assertEquals(
            List.of(new Column("setting", ColumnType.ENUM, false, null, "stage_setting")),
            table(schema, "stages").columns()
        );
    }

    /**
     * {@code ALTER TYPE ... RENAME VALUE} renames one label in its position, so
     * the modeled labels keep PostgreSQL's sort order.
     */
    @Test
    void shouldRenameAnEnumLabelInItsPosition() {
        Schema schema = parser.parse(
            parser.parse(
                enumSchema(),
                "ALTER TYPE stage_setting ADD VALUE 'covered' BEFORE 'outdoor';"
            ),
            "ALTER TYPE stage_setting RENAME VALUE 'covered' TO 'partly covered';"
        );

        assertEquals(
            List.of(
                new EnumType("stage_setting", List.of("indoor", "partly covered", "outdoor"))
            ),
            schema.enums()
        );
    }

    /**
     * {@code ALTER TYPE ... RENAME TO} renames the enum type in its position,
     * keeping its labels, and the columns of that type follow it, scalar and
     * array alike. A column of another enum type keeps its own type.
     */
    @Test
    void shouldRenameAModeledEnumAndTheColumnsOfIt() {
        Schema schema = parser.parse(
            parser.parse(
                parser.parse(enumSchema(), "CREATE TYPE shelf_state AS ENUM ('stocked');"),
                """
                    CREATE TABLE stages (
                        setting       stage_setting NOT NULL,
                        past_settings stage_setting[],
                        state         shelf_state
                    );
                    """
            ),
            "ALTER TYPE stage_setting RENAME TO stage_mood;"
        );

        assertEquals(
            List.of(
                new EnumType("stage_mood", List.of("indoor", "outdoor")),
                new EnumType("shelf_state", List.of("stocked"))
            ),
            schema.enums()
        );

        assertEquals(
            List.of(
                new Column("setting", ColumnType.ENUM, false, null, "stage_mood"),
                new Column("past_settings", ColumnType.ENUM, true, null, "stage_mood", true),
                new Column("state", ColumnType.ENUM, true, null, "shelf_state")
            ),
            table(schema, "stages").columns()
        );
    }

    /**
     * Both names of a rename are read without their SQL identifier delimiters,
     * as every other modeled name is.
     */
    @Test
    void shouldUnquoteBothNamesOfARenamedEnumType() {
        Schema schema = parser.parse(
            parser.parse(enumSchema(), "CREATE TABLE stages (setting stage_setting);"),
            "ALTER TYPE \"stage_setting\" RENAME TO \"Stage Mood\";"
        );

        assertEquals(
            List.of(new EnumType("Stage Mood", List.of("indoor", "outdoor"))),
            schema.enums()
        );

        assertEquals(
            List.of(new Column("setting", ColumnType.ENUM, true, null, "Stage Mood")),
            table(schema, "stages").columns()
        );
    }

    /**
     * A {@code CREATE TYPE ... AS ENUM} of a name a modeled enum already has
     * replaces that enum in its position, because the {@code DROP TYPE} that
     * freed the name is never seen.
     */
    @Test
    void shouldReplaceAModeledEnumByCreateTypeInItsPosition() {
        Schema schema = parser.parse(
            parser.parse(enumSchema(), "CREATE TYPE shelf_state AS ENUM ('stocked');"),
            "CREATE TYPE stage_setting AS ENUM ('indoor', 'covered', 'outdoor');"
        );

        assertEquals(
            List.of(
                new EnumType("stage_setting", List.of("indoor", "covered", "outdoor")),
                new EnumType("shelf_state", List.of("stocked"))
            ),
            schema.enums()
        );
    }

    /**
     * The replace-an-enum migration that renames the old type away, declares
     * the new one under the original name, and retypes the column loads, and
     * the column ends as a column of the newly declared type. The
     * {@code DROP TYPE} of the renamed type is ignored, so that type stays
     * modeled.
     */
    @Test
    void shouldLoadTheMigrationThatRenamesTheReplacedEnumAway() {
        Schema schema = parser.parse(
            parser.parse(
                enumSchema(),
                "CREATE TABLE stages (setting stage_setting NOT NULL);"
            ),
            """
                ALTER TYPE stage_setting RENAME TO stage_setting_old;

                CREATE TYPE stage_setting AS ENUM ('indoor', 'covered', 'outdoor');

                ALTER TABLE stages
                    ALTER COLUMN setting TYPE stage_setting USING setting::text::stage_setting;

                DROP TYPE stage_setting_old;
                """
        );

        assertEquals(
            List.of(
                new EnumType("stage_setting_old", List.of("indoor", "outdoor")),
                new EnumType("stage_setting", List.of("indoor", "covered", "outdoor"))
            ),
            schema.enums()
        );

        assertEquals(
            List.of(new Column("setting", ColumnType.ENUM, false, null, "stage_setting")),
            table(schema, "stages").columns()
        );
    }

    /**
     * The replace-an-enum migration that declares the new type beside the old
     * one, retypes the columns, and renames the new type onto the original name
     * loads: the one modeled enum carries the original name and the new labels,
     * and both columns name it again.
     */
    @Test
    void shouldLoadTheMigrationThatRenamesTheNewEnumOntoTheReplacedName() {
        Schema schema = parser.parse(
            parser.parse(
                enumSchema(),
                """
                    CREATE TABLE stages (
                        setting       stage_setting NOT NULL,
                        past_settings stage_setting[]
                    );
                    """
            ),
            """
                CREATE TYPE stage_setting_new AS ENUM ('indoor', 'covered', 'outdoor');

                ALTER TABLE stages
                    ALTER COLUMN setting TYPE stage_setting_new
                        USING setting::text::stage_setting_new;

                ALTER TABLE stages
                    ALTER COLUMN past_settings TYPE stage_setting_new[]
                        USING past_settings::text[]::stage_setting_new[];

                DROP TYPE stage_setting;

                ALTER TYPE stage_setting_new RENAME TO stage_setting;
                """
        );

        assertEquals(
            List.of(
                new EnumType("stage_setting", List.of("indoor", "covered", "outdoor"))
            ),
            schema.enums()
        );

        assertEquals(
            List.of(
                new Column("setting", ColumnType.ENUM, false, null, "stage_setting"),
                new Column("past_settings", ColumnType.ENUM, true, null, "stage_setting", true)
            ),
            table(schema, "stages").columns()
        );
    }

    /**
     * {@code OWNER TO} and the attribute actions, which belong to a composite
     * type, cannot change a modeled enum and are ignored without the stated
     * type being resolved at all, so a type sqlcj does not model, qualified or
     * not, does not fail.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALTER TYPE stage_setting OWNER TO app;",
            "ALTER TYPE status OWNER TO app;",
            "ALTER TYPE public.status OWNER TO app;",
            "ALTER TYPE stage_setting RENAME ATTRIBUTE a TO b;",
            "ALTER TYPE status RENAME ATTRIBUTE a TO b;",
            "ALTER TYPE stage_setting ADD ATTRIBUTE c text;",
            "ALTER TYPE stage_setting DROP ATTRIBUTE c;",
            "ALTER TYPE stage_setting ALTER ATTRIBUTE c TYPE int;",
            "ALTER TYPE status ADD ATTRIBUTE c text;"
        }
    )
    void shouldIgnoreTheAlterTypeActionsThatCannotChangeAModeledEnum(String statement) {
        Schema schema = parser.parse(enumSchema(), statement);

        assertEquals(
            List.of(new EnumType("stage_setting", List.of("indoor", "outdoor"))),
            schema.enums()
        );
    }

    /**
     * {@code RENAME TO} and {@code SET SCHEMA} of a type sqlcj does not model
     * are ignored, because such a type is none of its enum types.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ALTER TYPE status RENAME TO state;",
            "ALTER TYPE status SET SCHEMA archive;"
        }
    )
    void shouldIgnoreRenamingAndMovingATypeItDoesNotModel(String statement) {
        Schema schema = parser.parse(enumSchema(), statement);

        assertEquals(
            List.of(new EnumType("stage_setting", List.of("indoor", "outdoor"))),
            schema.enums()
        );
    }

    /**
     * {@code SET SCHEMA} of a modeled enum is rejected, because its columns
     * keep the type in a namespace sqlcj does not model. The failure names the
     * action and the line its own statement begins on.
     */
    @Test
    void shouldRejectSetSchemaOfAModeledEnum() {
        Schema schema = enumSchema();

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(schema, """
                ALTER TYPE stage_setting OWNER TO app;

                ALTER TYPE stage_setting SET SCHEMA archive;
                """)
        );

        assertEquals(
            "Unsupported ALTER TYPE action: SET SCHEMA archive at line 3",
            exception.getMessage()
        );
    }

    /** A schema declaring the enum type the enum tests apply statements to. */
    private Schema enumSchema() {
        return parser.parse("CREATE TYPE stage_setting AS ENUM ('indoor', 'outdoor');");
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
