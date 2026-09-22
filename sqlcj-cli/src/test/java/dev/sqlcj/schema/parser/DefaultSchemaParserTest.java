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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSchemaParserTest {

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
    @ValueSource(
        strings = {
            "SERIAL",
            "BIGSERIAL"
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

    @ParameterizedTest
    @ValueSource(
        strings = {
            "TIMESTAMPTZ",
            "SERIAL4",
            "SERIAL8",
            "SMALLSERIAL",
            "TIMESTAMP WITHOUT TIME ZONE"
        }
    )
    void shouldRejectUnsupportedColumnType(String sqlType) {
        String sql = """
            CREATE TABLE users (
                value %s
            );
            """
            .formatted(sqlType);

        assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
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

            ALTER TABLE users ADD COLUMN name VARCHAR(255);
            """;

        assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
        );
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
