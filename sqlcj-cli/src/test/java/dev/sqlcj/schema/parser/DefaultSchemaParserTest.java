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
            "character(3), VARCHAR"
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
     */
    @ParameterizedTest
    @CsvSource(
        {
            "JSONB, JSONB",
            "DOUBLE PRECISION, DOUBLE PRECISION",
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

        assertThrows(
            UnsupportedOperationException.class,
            () -> parser.parse(sql)
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
            "Unsupported schema statement: Alter",
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
            "Unsupported schema statement: Alter",
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
            "Unsupported schema statement: Alter",
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
