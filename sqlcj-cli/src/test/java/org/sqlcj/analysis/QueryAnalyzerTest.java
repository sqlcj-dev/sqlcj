package org.sqlcj.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryType;
import org.sqlcj.schema.Column;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.Table;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.SqlParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerTest {

    private static final Schema schema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("active", ColumnType.BOOLEAN, true)
                ),
                List.of()
            )
        )
    );

    /**
     * A schema whose {@code stages} table carries a column of each declared
     * enum type, beside a text column.
     */
    private static final Schema enumSchema = new Schema(
        List.of(
            new Table(
                "stages",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("setting", ColumnType.ENUM, false, null, "stage_setting"),
                    new Column("state", ColumnType.ENUM, true, null, "shelf_state"),
                    new Column("handle", ColumnType.TEXT, true)
                ),
                List.of()
            )
        ),
        List.of(
            new EnumType("stage_setting", List.of("indoor", "outdoor")),
            new EnumType("shelf_state", List.of("stocked"))
        )
    );

    /**
     * A schema whose {@code stages} table carries a one-dimensional array of a
     * mapped scalar type and of a declared enum type, beside the scalar columns
     * of the same types.
     */
    private static final Schema arraySchema = new Schema(
        List.of(
            new Table(
                "stages",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("tags", ColumnType.VARCHAR, true, null, null, true),
                    new Column("scores", ColumnType.INTEGER, false, null, null, true),
                    new Column(
                        "past_settings",
                        ColumnType.ENUM,
                        true,
                        null,
                        "stage_setting",
                        true
                    ),
                    new Column("setting", ColumnType.ENUM, false, null, "stage_setting"),
                    new Column("handle", ColumnType.TEXT, true)
                ),
                List.of()
            )
        ),
        List.of(new EnumType("stage_setting", List.of("indoor", "outdoor")))
    );

    /**
     * A schema whose {@code users} table carries two columns of one type, a
     * blank-padded character column, an enum column, and a column of every kind
     * an array parameter cannot carry, for the list predicate.
     */
    private static final Schema anyListSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("parent_id", ColumnType.BIGINT, true),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("code", ColumnType.VARCHAR, true, null, null, false, true),
                    new Column("role", ColumnType.ENUM, true, null, "user_role"),
                    new Column("tags", ColumnType.VARCHAR, true, null, null, true),
                    new Column("avatar", ColumnType.BYTEA, true),
                    new Column("payload", ColumnType.JSON, true),
                    new Column("document", ColumnType.JSONB, true)
                ),
                List.of()
            )
        ),
        List.of(new EnumType("user_role", List.of("admin", "member")))
    );

    /** A schema with both text column types, for the text predicate forms. */
    private static final Schema predicateSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("bio", ColumnType.TEXT, true)
                ),
                List.of()
            )
        )
    );

    /**
     * A schema whose {@code users} table carries one column of every kind a
     * projected aggregate reads: each scalar type {@code SUM} and {@code AVG}
     * are defined for, the orderable text, temporal, enum, array, and
     * blank-padded columns {@code MIN} and {@code MAX} take, each scalar type
     * they are not defined for, and the {@code tags} column the parser
     * recorded without a mapped type.
     */
    private static final Schema aggregateSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("score", ColumnType.SMALLINT, true),
                    new Column("visits", ColumnType.INTEGER, false),
                    new Column("balance", ColumnType.DECIMAL, true),
                    new Column("ratio", ColumnType.REAL, true),
                    new Column("weight", ColumnType.DOUBLE_PRECISION, true),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("bio", ColumnType.TEXT, true),
                    new Column("created_at", ColumnType.TIMESTAMP, true),
                    new Column("code", ColumnType.VARCHAR, true, null, null, false, true),
                    new Column("role", ColumnType.ENUM, true, null, "user_role"),
                    new Column("labels", ColumnType.VARCHAR, true, null, null, true),
                    new Column("active", ColumnType.BOOLEAN, true),
                    new Column("external_id", ColumnType.UUID, true),
                    new Column("avatar", ColumnType.BYTEA, true),
                    new Column("payload", ColumnType.JSON, true),
                    new Column("document", ColumnType.JSONB, true),
                    new Column("tags", null, true, "JSONB[]")
                ),
                List.of()
            )
        ),
        List.of(new EnumType("user_role", List.of("admin", "member")))
    );

    /**
     * A schema whose {@code tags} column the parser recorded without a mapped
     * type, beside the supported columns of the same table.
     */
    private static final Schema unsupportedTypeSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("tags", null, true, "JSONB[]")
                ),
                List.of()
            )
        )
    );

    /**
     * A schema whose {@code users} table carries the columns a non-binding
     * write value targets beside the bound ones.
     */
    private static final Schema writeSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("nickname", ColumnType.VARCHAR, true),
                    new Column("bio", ColumnType.TEXT, true),
                    new Column("active", ColumnType.BOOLEAN, true),
                    new Column("version", ColumnType.INTEGER, false),
                    new Column("updated_at", ColumnType.TIMESTAMP, true)
                ),
                List.of()
            )
        )
    );

    /**
     * A schema whose {@code memberships} table is written from several
     * {@code VALUES} rows and from the rows of the {@code invitations} and
     * {@code users} tables. {@code invitations} carries exactly the two columns
     * a wildcard item expands to, and {@code labels} is the array column a
     * subscripted target writes one element of.
     */
    private static final Schema insertSourceSchema = new Schema(
        List.of(
            new Table(
                "memberships",
                List.of(
                    new Column("project_id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false),
                    new Column("role", ColumnType.VARCHAR, true),
                    new Column("labels", ColumnType.VARCHAR, true, null, null, true)
                ),
                List.of()
            ),
            new Table(
                "invitations",
                List.of(
                    new Column("project_id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false)
                ),
                List.of()
            ),
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("active", ColumnType.BOOLEAN, true)
                ),
                List.of()
            )
        )
    );

    /**
     * A schema whose {@code users} table carries the text and {@code JSONB}
     * columns the untyped-placeholder diagnostics read around a placeholder.
     */
    private static final Schema untypedSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("email", ColumnType.TEXT, true),
                    new Column("metadata", ColumnType.JSONB, true)
                ),
                List.of()
            )
        )
    );

    private static final Schema joinSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("name", ColumnType.VARCHAR, true)
                ),
                List.of()
            ),
            new Table(
                "profiles",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false),
                    new Column("nickname", ColumnType.VARCHAR, true)
                ),
                List.of()
            ),
            new Table(
                "orders",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false),
                    new Column("total", ColumnType.DECIMAL, true)
                ),
                List.of()
            )
        )
    );

    /**
     * A schema whose {@code users} table carries one column of every type a set
     * operation resolves — a nullable and a non-null column of one type, each
     * scalar type, a blank-padded character column, two declared enum types,
     * and an array of a scalar and of an enum type — beside the
     * {@code profiles} table, whose complete row a branch returns.
     */
    private static final Schema setOperationSchema = new Schema(
        List.of(
            new Table(
                "users",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("parent_id", ColumnType.BIGINT, true),
                    new Column("score", ColumnType.SMALLINT, true),
                    new Column("visits", ColumnType.INTEGER, true),
                    new Column("balance", ColumnType.DECIMAL, true),
                    new Column("ratio", ColumnType.REAL, true),
                    new Column("weight", ColumnType.DOUBLE_PRECISION, true),
                    new Column("name", ColumnType.VARCHAR, true),
                    new Column("bio", ColumnType.TEXT, true),
                    new Column("code", ColumnType.VARCHAR, true, null, null, false, true),
                    new Column("birth_date", ColumnType.DATE, true),
                    new Column("opened_at", ColumnType.TIME, true),
                    new Column("created_at", ColumnType.TIMESTAMP, true),
                    new Column("updated_at", ColumnType.TIMESTAMP_WITH_TIME_ZONE, true),
                    new Column("active", ColumnType.BOOLEAN, true),
                    new Column("external_id", ColumnType.UUID, true),
                    new Column("avatar", ColumnType.BYTEA, true),
                    new Column("payload", ColumnType.JSON, true),
                    new Column("document", ColumnType.JSONB, true),
                    new Column("role", ColumnType.ENUM, true, null, "user_role"),
                    new Column("state", ColumnType.ENUM, true, null, "user_state"),
                    new Column("past_roles", ColumnType.ENUM, true, null, "user_role", true),
                    new Column("labels", ColumnType.VARCHAR, true, null, null, true),
                    new Column("scores", ColumnType.INTEGER, true, null, null, true)
                ),
                List.of()
            ),
            new Table(
                "profiles",
                List.of(
                    new Column("id", ColumnType.BIGINT, false),
                    new Column("user_id", ColumnType.BIGINT, false),
                    new Column("nickname", ColumnType.VARCHAR, true)
                ),
                List.of()
            )
        ),
        List.of(
            new EnumType("user_role", List.of("admin", "member")),
            new EnumType("user_state", List.of("active", "retired"))
        )
    );

    private final SqlParser parser = new SqlParser();
    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    @Test
    void shouldAnalyzeSelectWithoutParameters() {
        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users;
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, parsedSql, schema);

        assertEquals("ListUsers", model.name());
        assertEquals(QueryType.MANY, model.type());
        assertEquals("users", model.table());
        assertTrue(model.parameters().isEmpty());
    }

    @Test
    void shouldAnalyzeSelectWithSingleParameter() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            """
                SELECT *
                FROM users
                WHERE id = $1;
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, parsedSql, schema);

        assertEquals("users", model.table());
        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldAnalyzeSelectWithMultipleParameters() {
        Query query = new Query(
            "ListUsersByIdAndName",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id = $1
                  AND name = $2;
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, parsedSql, schema);

        assertEquals("users", model.table());
        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldAnalyzeInsert() {
        Query query = new Query(
            "InsertUser",
            QueryType.EXEC,
            """
                INSERT INTO users (id, name)
                VALUES ($1, $2)
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("InsertUser", model.name());
        assertEquals(QueryType.EXEC, model.type());
        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(1, 2),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldAnalyzeUpdate() {
        Query query = new Query(
            "UpdateUser",
            QueryType.EXEC,
            """
                UPDATE users
                SET name = $2,
                    active = $3
                WHERE id = $1
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("UpdateUser", model.name());
        assertEquals(QueryType.EXEC, model.type());
        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            """
                UPDATE users
                SET name = ?,
                    active = ?
                WHERE id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(2, 3, 1),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldAnalyzeDelete() {
        Query query = new Query(
            "DeleteUser",
            QueryType.EXEC,
            """
                DELETE FROM users
                WHERE id = $1
                  AND active = $2
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("DeleteUser", model.name());
        assertEquals(QueryType.EXEC, model.type());
        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            """
                DELETE FROM users
                WHERE id = ?
                  AND active = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(1, 2),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldAnalyzeDeleteWithoutWhere() {
        Query query = new Query(
            "DeleteUsers",
            QueryType.EXEC,
            "DELETE FROM users"
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());
        assertTrue(model.parameters().isEmpty());
        assertTrue(model.bindingParameterIndexes().isEmpty());
    }

    @Test
    void shouldRejectWriteWithoutExecQueryType() {
        Query query = new Query(
            "InsertUser",
            QueryType.ONE,
            """
                INSERT INTO users (id)
                VALUES ($1)
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Write queries without RETURNING must be declared as :exec",
            exception.getMessage()
        );
    }

    @Test
    void shouldAnalyzeSelectWithExplicitColumns() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT id, name FROM users WHERE id = $1"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals("GetUser", model.name());
        assertEquals(QueryType.ONE, model.type());
        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A parenthesized {@code SELECT} is the query it encloses, at any depth, so
     * it analyzes to the model of that query.
     */
    @Test
    void shouldAnalyzeParenthesizedSelectAsTheQueryItEncloses() {
        String sql = "((SELECT id FROM users WHERE id = $1))";

        QueryModel model = analyzer.analyze(
            new Query("GetUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
        assertEquals("((SELECT id FROM users WHERE id = ?))", model.executableSql());
    }

    @Test
    void shouldResolveAllColumns() {
        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            "SELECT * FROM users"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("active", ColumnType.BOOLEAN, true)
            ),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
    }

    @Test
    void shouldResolveParameterTypeFromColumn() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE id = $1"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldResolveMultipleParameters() {
        Query query = new Query(
            "ListUsersByIdAndName",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id = $1
                  AND name = $2
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );
    }

    @Test
    void shouldNotCreateParametersForLiteralExpressions() {
        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE 1 = 1"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertTrue(model.parameters().isEmpty());
    }

    @Test
    void shouldThrowWhenTableDoesNotExist() {
        Query query = new Query(
            "GetOrder",
            QueryType.ONE,
            "SELECT * FROM orders"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );
    }

    @Test
    void shouldThrowWhenColumnDoesNotExist() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT username FROM users"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );
    }

    @Test
    void shouldResolveQueryParameterFromReferencedColumn() {
        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE id = $1"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().get(0);

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveMultipleQueryParameters() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id = $1 AND active = $2"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @Test
    void shouldResolveMultipleParametersForSameColumn() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id = $1 AND id = $2"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());
    }

    @Test
    void shouldResolveQueryParametersInIndexOrder() {
        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE active = $2 AND id = $1"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @Test
    void shouldResolveQueryParametersInsideOrExpression() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id = $1 OR active = $2"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @Test
    void shouldResolveQueryParametersInsideNestedAndOrExpressions() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id = $1
                  AND (active = $2 OR name = $3)
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(3, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("name", third.name());
        assertEquals(ColumnType.VARCHAR, third.type());
    }

    @Test
    void shouldResolveQueryParametersForComparisonOperators() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id > $1
                  AND active = $2
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(2, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("active", second.name());
        assertEquals(ColumnType.BOOLEAN, second.type());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "=",
            "<>",
            ">",
            ">=",
            "<",
            "<="
        }
    )
    void shouldResolveQueryParameterForComparisonOperator(
        String operator
    ) {
        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            """
                SELECT *
                FROM users
                WHERE id %s $1
                """
                .formatted(operator)
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().get(0);

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveQueryParameterWhenParameterIsOnLeftSide() {
        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            "SELECT * FROM users WHERE $1 = id"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().get(0);

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveLikePatternParameterInTextualBindingOrder() {
        String sql = "SELECT id FROM users WHERE name ILIKE $2 AND id > $1";

        QueryModel model = analyzer.analyze(
            new Query("SearchUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE name ILIKE ? AND id > ?",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveLikePatternParameterFromTextColumn() {
        String sql = "SELECT id FROM users WHERE bio LIKE $1";

        QueryModel model = analyzer.analyze(
            new Query("SearchUsers", QueryType.MANY, sql),
            parser.parse(sql),
            predicateSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "bio", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    @Test
    void shouldNotCreateParameterForLiteralLikePattern() {
        String sql = "SELECT id FROM users WHERE name LIKE 'A%' AND id = $1";

        QueryModel model = analyzer.analyze(
            new Query("SearchUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM users WHERE name LIKE 'A%' AND id = ?",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveNullPredicateColumnsWithoutParameters() {
        String sql = """
            SELECT u.id
            FROM users u
            WHERE u.bio IS NOT NULL
              AND bio IS NULL
              AND u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.OPTIONAL, sql),
            parser.parse(sql),
            predicateSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    @Test
    void shouldRejectUnknownColumnInNullPredicate() {
        String sql = "SELECT id FROM users WHERE missing IS NULL";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Column not found in sources users: missing",
            exception.getMessage()
        );
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users WHERE name NOT LIKE $1"
                + "|A negated LIKE pattern placeholder is not supported.",
            "SELECT id FROM users WHERE name NOT ILIKE $1"
                + "|A negated LIKE pattern placeholder is not supported.",
            "SELECT id FROM users WHERE name SIMILAR TO $1"
                + "|Only LIKE and ILIKE pattern placeholders are supported, but was: SIMILAR_TO",
            "SELECT id FROM users WHERE name LIKE $1 ESCAPE '!'"
                + "|A LIKE pattern placeholder must not have an ESCAPE clause.",
            "SELECT id FROM users WHERE name LIKE BINARY $1"
                + "|A binary LIKE pattern placeholder is not supported.",
            "SELECT id FROM users WHERE id LIKE $1"
                + "|A LIKE pattern placeholder requires a VARCHAR or TEXT column, but id is BIGINT.",
            "SELECT id FROM users WHERE $1 LIKE name"
                + "|A LIKE placeholder must be the pattern, not the tested value.",
            "SELECT id FROM users WHERE :pattern LIKE name"
                + "|A LIKE placeholder must be the pattern, not the tested value."
        }
    )
    void shouldRejectUnsupportedLikePlaceholderForm(String sql, String message) {
        Query query = new Query("SearchUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(message, exception.getMessage());
    }

    @Test
    void shouldResolveRangeBoundParametersFromTestedColumn() {
        String sql = "SELECT id FROM users WHERE id BETWEEN $1 AND $2";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE id BETWEEN ? AND ?",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveNegatedRangeBoundParameters() {
        String sql = "SELECT id FROM users WHERE id NOT BETWEEN $1 AND $2";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE id NOT BETWEEN ? AND ?",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveRangeBoundParametersInTextualBindingOrder() {
        String sql = "SELECT id FROM users WHERE name = $1 AND id NOT BETWEEN $3 AND $2";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "id", ColumnType.BIGINT),
                new QueryParameter(3, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 3, 2), model.bindingParameterIndexes());
    }

    @Test
    void shouldResolveRangeBoundParameterBesideLiteralBound() {
        String sql = "SELECT id FROM users WHERE id BETWEEN $1 AND 10";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE id BETWEEN ? AND 10",
            model.executableSql()
        );
    }

    @Test
    void shouldNotCreateParameterForLiteralRange() {
        String sql = "SELECT id FROM users WHERE id BETWEEN 1 AND 10 AND name = $1";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM users WHERE id BETWEEN 1 AND 10 AND name = ?",
            model.executableSql()
        );
    }

    @Test
    void shouldRejectUnknownColumnInRangePredicate() {
        String sql = "SELECT id FROM users WHERE missing BETWEEN $1 AND $2";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Column not found in sources users: missing",
            exception.getMessage()
        );
    }

    @Test
    void shouldResolveNamedRangeBoundParameters() {
        String sql = "SELECT id FROM users WHERE id BETWEEN :lo AND :hi";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "lo", ColumnType.BIGINT),
                new QueryParameter(2, "hi", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE id BETWEEN ? AND ?",
            model.executableSql()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users WHERE name LIKE '%' || $1 || '%'",
            "SELECT id FROM users WHERE $1 IS NULL",
            "SELECT id FROM users WHERE $1 BETWEEN id AND id",
            "SELECT id FROM users WHERE id BETWEEN $1 + 1 AND $2"
        }
    )
    void shouldRejectPlaceholderThatIsNotAnAnalyzedPredicateOperand(String sql) {
        Query query = new Query("SearchUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );
    }

    @Test
    void shouldResolvePaginationParametersAfterPredicateParameters() {
        String sql = "SELECT id FROM users WHERE name = $1 ORDER BY id LIMIT $2 OFFSET $3";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "limit", ColumnType.INTEGER),
                new QueryParameter(3, "offset", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE name = ? ORDER BY id LIMIT ? OFFSET ?",
            model.executableSql()
        );
    }

    @Test
    void shouldResolvePaginationParametersInTextualBindingOrder() {
        String sql = "SELECT id FROM users ORDER BY id OFFSET $2 LIMIT $1";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "limit", ColumnType.INTEGER),
                new QueryParameter(2, "offset", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users ORDER BY id OFFSET ? LIMIT ?",
            model.executableSql()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users ORDER BY id LIMIT 10 OFFSET $1",
            "SELECT id FROM users ORDER BY id LIMIT ALL OFFSET $1"
        }
    )
    void shouldResolveOffsetParameterBesideUnanalyzedRowCount(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "offset", ColumnType.INTEGER)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    @Test
    void shouldNotCreateParametersForLiteralPagination() {
        String sql = "SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 5";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertTrue(model.parameters().isEmpty());
        assertTrue(model.bindingParameterIndexes().isEmpty());

        assertEquals(
            "SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 5",
            model.executableSql()
        );
    }

    /**
     * A named pagination value is named after its placeholder rather than after
     * its clause, so the generated method states the caller's own name.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users ORDER BY id LIMIT :pageSize",
            "SELECT id FROM users ORDER BY id OFFSET :pageSize"
        }
    )
    void shouldNameNamedPaginationParameterAfterItsPlaceholder(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "pageSize", ColumnType.INTEGER)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A cast pagination value states its own type and keeps its clause's name.
     */
    @Test
    void shouldResolveCastPaginationParameters() {
        String sql = "SELECT id FROM users ORDER BY id LIMIT $1::int OFFSET $2::int";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "limit", ColumnType.INTEGER),
                new QueryParameter(2, "offset", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users ORDER BY id LIMIT ?::int OFFSET ?::int",
            model.executableSql()
        );
    }

    /**
     * The parser stores an {@code OFFSET} written before its {@code LIMIT} like
     * one written after it, so the two cast values bind in the order the query
     * writes them.
     */
    @Test
    void shouldResolveCastPaginationParametersInTextualBindingOrder() {
        String sql = "SELECT id FROM users ORDER BY id OFFSET $2::int LIMIT $1::int";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "limit", ColumnType.INTEGER),
                new QueryParameter(2, "offset", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());
    }

    /**
     * A cast pagination value is typed by its cast rather than by its clause,
     * and a named one is still named after its placeholder.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "SELECT id FROM users ORDER BY id LIMIT $1::bigint|limit|BIGINT",
            "SELECT id FROM users ORDER BY id OFFSET $1::bigint|offset|BIGINT",
            "SELECT id FROM users ORDER BY id LIMIT :pageSize::int|pageSize|INTEGER",
            "SELECT id FROM users ORDER BY id OFFSET CAST(:skip AS int)|skip|INTEGER"
        }
    )
    void shouldTypeCastPaginationParameterByItsCast(String sql, String name, ColumnType type) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, name, type)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users ORDER BY id LIMIT $1 + 1",
            "SELECT id FROM users ORDER BY id OFFSET $1 + 1",
            "SELECT id FROM users ORDER BY id LIMIT 5, $1",
            "SELECT id FROM users ORDER BY id LIMIT $1, $2",
            "SELECT id FROM users ORDER BY id FETCH FIRST $1 ROWS ONLY"
        }
    )
    void shouldRejectPlaceholderInUnsupportedPaginationValue(String sql) {
        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );
    }

    @Test
    void shouldResolveScalarCountColumnFromItsAlias() {
        String sql = "SELECT COUNT(*) AS total FROM users";

        QueryModel model = analyzer.analyze(
            new Query("CountUsers", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryColumn("total", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
        assertNull(model.rowTable());
    }

    @Test
    void shouldResolveScalarCountBesidePredicateParameter() {
        String sql = "SELECT count(*) user_count FROM users WHERE name LIKE $1";

        QueryModel model = analyzer.analyze(
            new Query("CountMatchingUsers", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryColumn("user_count", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    /**
     * A grouped count stands beside a direct column and keeps its own
     * position, while the grouped result is no longer one complete table row.
     */
    @Test
    void shouldResolveScalarCountBesideDirectColumn() {
        String sql = "SELECT name, COUNT(*) AS n FROM users GROUP BY name";

        QueryModel model = analyzer.analyze(
            new Query("CountUsersByName", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("n", ColumnType.BIGINT, false)
            ),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
        assertNull(model.rowTable());
    }

    /** A count written before the grouped column keeps that position. */
    @Test
    void shouldResolveScalarCountBeforeDirectColumn() {
        String sql = "SELECT COUNT(*) AS n, name FROM users GROUP BY name";

        QueryModel model = analyzer.analyze(
            new Query("CountUsersByName", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("n", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /** A grouped count keeps the parameters of its predicate. */
    @Test
    void shouldResolveGroupedCountBesidePredicateParameter() {
        String sql = "SELECT name, COUNT(*) AS n FROM users WHERE active = $1 GROUP BY name";

        QueryModel model = analyzer.analyze(
            new Query("CountActiveUsersByName", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("n", ColumnType.BIGINT, false)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "active", ColumnType.BOOLEAN)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * An aliased {@code COUNT} of one argument is a non-null {@code BIGINT}
     * however the argument is written: with {@code DISTINCT} or {@code ALL}, as
     * a qualified wildcard, as a column of a type sqlcj does not map, and as an
     * expression that reaches the database as written.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT COUNT(id) AS n FROM users",
            "SELECT COUNT(DISTINCT id) AS n FROM users",
            "SELECT COUNT(ALL id) AS n FROM users",
            "SELECT count(u.*) AS n FROM users u",
            "SELECT COUNT(DISTINCT u.*) AS n FROM users u",
            "SELECT COUNT(tags) AS n FROM users",
            "SELECT COUNT(lower(name)) AS n FROM users",
            "SELECT COUNT(1) AS n FROM users"
        }
    )
    void shouldTypeCountOfOneArgumentAsANonNullBigint(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("CountUsers", QueryType.ONE, sql),
            parser.parse(sql),
            aggregateSchema
        );

        assertEquals(
            List.of(new QueryColumn("n", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
        assertNull(model.rowTable());
    }

    /**
     * A {@code COUNT} of a left-joined column is non-null too, because a group
     * whose join found no match counts {@code 0} rows rather than none.
     */
    @Test
    void shouldTypeCountOfALeftJoinedColumnAsNonNull() {
        String sql = """
            SELECT u.id, COUNT(o.id) AS orders
            FROM users u
                     LEFT JOIN orders o ON o.user_id = u.id
            GROUP BY u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("CountUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("orders", ColumnType.BIGINT, false)
            ),
            model.columns()
        );
    }

    /**
     * An aliased {@code MIN} or {@code MAX} is a nullable column of its own
     * column's type, because it reads {@code NULL} over no row, whether the
     * column is numeric, textual, temporal, or blank padded.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "SELECT MIN(id) AS n FROM users|BIGINT",
            "SELECT MAX(score) AS n FROM users|SMALLINT",
            "SELECT MIN(visits) AS n FROM users|INTEGER",
            "SELECT MAX(balance) AS n FROM users|DECIMAL",
            "SELECT MIN(ratio) AS n FROM users|REAL",
            "SELECT MAX(weight) AS n FROM users|DOUBLE_PRECISION",
            "SELECT max(name) AS n FROM users|VARCHAR",
            "SELECT MIN(bio) AS n FROM users|TEXT",
            "SELECT MAX(created_at) AS n FROM users|TIMESTAMP",
            "SELECT MIN(code) AS n FROM users|VARCHAR",
            "SELECT MAX(DISTINCT name) AS n FROM users|VARCHAR",
            "SELECT MIN(u.created_at) AS n FROM users u|TIMESTAMP"
        }
    )
    void shouldTypeExtremumAggregateFromItsColumn(String sql, ColumnType type) {
        QueryModel model = analyzer.analyze(
            new Query("ReadUserExtremum", QueryType.ONE, sql),
            parser.parse(sql),
            aggregateSchema
        );

        assertEquals(
            List.of(new QueryColumn("n", type, true)),
            model.columns()
        );

        assertNull(model.rowTable());
    }

    /** A {@code MIN} or {@code MAX} of an enum column keeps its enum type. */
    @Test
    void shouldTypeExtremumAggregateOfAnEnumColumn() {
        String sql = "SELECT MAX(role) AS n FROM users";

        QueryModel model = analyzer.analyze(
            new Query("ReadUserExtremum", QueryType.ONE, sql),
            parser.parse(sql),
            aggregateSchema
        );

        assertEquals(
            List.of(new QueryColumn("n", ColumnType.ENUM, true, "user_role")),
            model.columns()
        );
    }

    /**
     * A {@code MIN} or {@code MAX} of an array column is an array of the
     * column's element type, because PostgreSQL orders arrays.
     */
    @Test
    void shouldTypeExtremumAggregateOfAnArrayColumn() {
        String sql = "SELECT MIN(labels) AS n FROM users";

        QueryModel model = analyzer.analyze(
            new Query("ReadUserExtremum", QueryType.ONE, sql),
            parser.parse(sql),
            aggregateSchema
        );

        assertEquals(
            List.of(new QueryColumn("n", ColumnType.VARCHAR, true, null, true)),
            model.columns()
        );
    }

    /**
     * An aliased {@code SUM} or {@code AVG} is a nullable column of the type
     * PostgreSQL 16 defines for its column's scalar type, which widens an
     * integral sum and makes every average exact or floating. It is nullable
     * even over a {@code NOT NULL} column, because it reads {@code NULL} over
     * no row.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "SELECT SUM(score) AS n FROM users|BIGINT",
            "SELECT SUM(visits) AS n FROM users|BIGINT",
            "SELECT SUM(id) AS n FROM users|DECIMAL",
            "SELECT sum(balance) AS n FROM users|DECIMAL",
            "SELECT SUM(ratio) AS n FROM users|REAL",
            "SELECT SUM(weight) AS n FROM users|DOUBLE_PRECISION",
            "SELECT AVG(score) AS n FROM users|DECIMAL",
            "SELECT AVG(visits) AS n FROM users|DECIMAL",
            "SELECT AVG(id) AS n FROM users|DECIMAL",
            "SELECT avg(balance) AS n FROM users|DECIMAL",
            "SELECT AVG(DISTINCT ratio) AS n FROM users|DOUBLE_PRECISION",
            "SELECT AVG(weight) AS n FROM users|DOUBLE_PRECISION"
        }
    )
    void shouldTypeSumAndAverageByTheirColumnType(String sql, ColumnType type) {
        QueryModel model = analyzer.analyze(
            new Query("ReadUserTotal", QueryType.ONE, sql),
            parser.parse(sql),
            aggregateSchema
        );

        assertEquals(
            List.of(new QueryColumn("n", type, true)),
            model.columns()
        );

        assertNull(model.rowTable());
    }

    /**
     * A derived source exposes an aggregate as a column of its own, so the
     * outer query of a common table expression and of a subquery source reads
     * its type, its nullability, and the blank-padded spelling a {@code MIN}
     * carries, without restating a cast.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            """
                WITH stats AS (SELECT COUNT(id) AS visits, MIN(code) AS first_code
                               FROM users)
                SELECT stats.visits, stats.first_code
                FROM stats
                WHERE stats.first_code = $1
                """,
            """
                SELECT stats.visits, stats.first_code
                FROM (SELECT COUNT(id) AS visits, MIN(code) AS first_code
                      FROM users) stats
                WHERE stats.first_code = $1
                """
        }
    )
    void shouldReadAnAggregateColumnThroughADerivedSource(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUserStats", QueryType.MANY, sql),
            parser.parse(sql),
            aggregateSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("visits", ColumnType.BIGINT, false),
                new QueryColumn("first_code", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "first_code", ColumnType.VARCHAR, null, false, true)
            ),
            model.parameters()
        );
    }

    /**
     * An aggregate names no result column of its own, so a missing alias is
     * rejected naming the aggregate as the query writes it, in upper and in
     * lower case.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT COUNT(id) FROM users"
                + "|COUNT(id) requires a result alias, such as COUNT(id) AS total.",
            "SELECT count(DISTINCT id) FROM users"
                + "|count(DISTINCT id) requires a result alias,"
                + " such as count(DISTINCT id) AS total.",
            "SELECT MAX(created_at) FROM users"
                + "|MAX(created_at) requires a result alias,"
                + " such as MAX(created_at) AS total.",
            "SELECT sum(balance) FROM users"
                + "|sum(balance) requires a result alias, such as sum(balance) AS total.",
            "SELECT AVG(score) FROM users"
                + "|AVG(score) requires a result alias, such as AVG(score) AS total."
        }
    )
    void shouldRejectAnAggregateWithoutAResultAlias(String sql, String message) {
        Query query = new Query("ReadUserTotal", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, aggregateSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * An aliased cast projection is a nullable result column of the cast type,
     * in either cast spelling and with or without {@code AS}. Its operand is
     * not analyzed and reaches the database as written.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT SUM(id)::numeric AS total FROM users",
            "SELECT CAST(SUM(id) AS numeric) AS total FROM users",
            "SELECT SUM(id)::numeric total FROM users"
        }
    )
    void shouldResolveCastProjectionColumnFromItsAlias(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("SumUserIds", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryColumn("total", ColumnType.DECIMAL, true)),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
        assertNull(model.rowTable());
    }

    /** A cast projection may name a declared enum type. */
    @Test
    void shouldResolveEnumCastProjectionColumn() {
        String sql = "SELECT handle::stage_setting AS s FROM stages";

        QueryModel model = analyzer.analyze(
            new Query("ListStageSettings", QueryType.MANY, sql),
            parser.parse(sql),
            enumSchema
        );

        assertEquals(
            List.of(new QueryColumn("s", ColumnType.ENUM, true, "stage_setting")),
            model.columns()
        );
    }

    /** A cast projection of a one-dimensional array is an array column. */
    @Test
    void shouldResolveArrayCastProjectionColumn() {
        String sql = "SELECT handle::text[] AS labels FROM stages";

        QueryModel model = analyzer.analyze(
            new Query("ListStageLabels", QueryType.MANY, sql),
            parser.parse(sql),
            arraySchema
        );

        assertEquals(
            List.of(new QueryColumn("labels", ColumnType.TEXT, true, null, true)),
            model.columns()
        );
    }

    /**
     * A cast projection without an alias names no result column, and a cast
     * type sqlcj does not map is rejected naming the result column and the
     * declared type.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT SUM(id)::numeric FROM users"
                + "|A cast projection requires a result alias,"
                + " such as SUM(amount)::numeric AS total.",
            "SELECT CAST(SUM(id) AS numeric) FROM users"
                + "|A cast projection requires a result alias,"
                + " such as SUM(amount)::numeric AS total.",
            "SELECT id::interval AS i FROM users"
                + "|Result column 'i' has unsupported cast type INTERVAL"
        }
    )
    void shouldRejectUnsupportedCastProjectionForm(String sql, String message) {
        Query query = new Query("SumUserIds", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A cast projection whose operand is a placeholder types that placeholder.
     * No column names it, so it is named after its own index, and the cast
     * still names the result column after the projection's alias.
     */
    @Test
    void shouldResolveCastPlaceholderProjection() {
        String sql = "SELECT $1::text AS label, id FROM users WHERE id = $2";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("label", ColumnType.TEXT, true),
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "param1", ColumnType.TEXT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "SELECT ?::text AS label, id FROM users WHERE id = ?",
            model.executableSql()
        );
    }

    /**
     * A named placeholder is numbered by its first textual occurrence, so a
     * projected one is the query's first logical parameter even though the
     * {@code WHERE} clause is analyzed before the projection's placeholders.
     */
    @Test
    void shouldNumberNamedProjectionPlaceholderBeforeThePredicateOne() {
        String sql = "SELECT :label::text AS label, id FROM users WHERE id = :id";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "label", ColumnType.TEXT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * An aggregate form this subset types no result of keeps the
     * computed-projection rejection: a {@code *} written with {@code DISTINCT}
     * or {@code ALL}, which PostgreSQL's grammar admits no such {@code *} for,
     * a column type PostgreSQL defines the aggregate for none of, a
     * subscripted argument, a qualified name, two arguments, another function,
     * and the {@code FILTER} and {@code OVER} forms. A column of a type sqlcj
     * does not map keeps the unsupported-type rejection instead, and a
     * {@code COUNT(*)} without an alias keeps its own.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT COUNT(*) FROM users"
                + "|COUNT(*) requires a result alias, such as COUNT(*) AS total.",
            "SELECT name, COUNT(*) FROM users GROUP BY name"
                + "|COUNT(*) requires a result alias, such as COUNT(*) AS total.",
            "SELECT COUNT(DISTINCT *) AS n FROM users"
                + "|Cannot infer the type of COUNT(DISTINCT *) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT COUNT(ALL *) AS n FROM users"
                + "|Cannot infer the type of COUNT(ALL *) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT MAX(active) AS n FROM users"
                + "|Cannot infer the type of MAX(active) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT MIN(external_id) AS n FROM users"
                + "|Cannot infer the type of MIN(external_id) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT SUM(bio) AS n FROM users"
                + "|Cannot infer the type of SUM(bio) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT SUM(labels) AS n FROM users"
                + "|Cannot infer the type of SUM(labels) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT MIN(labels[1]) AS n FROM users"
                + "|Cannot infer the type of MIN(labels[1]) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT MAX(id, name) AS n FROM users"
                + "|Cannot infer the type of MAX(id, name) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT pg_catalog.count(*) AS n FROM users"
                + "|Cannot infer the type of pg_catalog.count(*) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT pg_catalog.max(id) AS n FROM users"
                + "|Cannot infer the type of pg_catalog.max(id) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT lower(name) AS n FROM users"
                + "|Cannot infer the type of lower(name) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT COUNT(*) FILTER (WHERE id > 1) AS n FROM users"
                + "|Cannot infer the type of COUNT(*) FILTER (WHERE id > 1) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT COUNT(*) OVER () AS n FROM users"
                + "|Cannot infer the type of COUNT(*) OVER () in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT MAX(tags) AS n FROM users"
                + "|Column 'tags' has unsupported type JSONB[]"
        }
    )
    void shouldRejectUnsupportedAggregateProjectionForm(String sql, String message) {
        Query query = new Query("CountUsers", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, aggregateSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    @Test
    void shouldResolveQueryParametersInsideInExpression() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id IN ($1, $2, $3)"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(3, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("id", third.name());
        assertEquals(ColumnType.BIGINT, third.type());
    }

    @Test
    void shouldResolveQueryParametersInsideInExpressionInIndexOrder() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            "SELECT * FROM users WHERE id IN ($3, $1, $2)"
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(3, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("id", third.name());
        assertEquals(ColumnType.BIGINT, third.type());
    }

    @Test
    void shouldResolveQueryParametersFromCombinedExpressions() {
        Query query = new Query(
            "FindUsers",
            QueryType.MANY,
            """
                SELECT *
                FROM users
                WHERE id IN ($1, $2)
                  AND (active = $3 OR name = $4)
                """
        );

        ParsedSql parsedSql = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
            query,
            parsedSql,
            schema
        );

        assertEquals(4, model.parameters().size());

        QueryParameter first = model.parameters().get(0);
        assertEquals(1, first.index());
        assertEquals("id", first.name());
        assertEquals(ColumnType.BIGINT, first.type());

        QueryParameter second = model.parameters().get(1);
        assertEquals(2, second.index());
        assertEquals("id", second.name());
        assertEquals(ColumnType.BIGINT, second.type());

        QueryParameter third = model.parameters().get(2);
        assertEquals(3, third.index());
        assertEquals("active", third.name());
        assertEquals(ColumnType.BOOLEAN, third.type());

        QueryParameter fourth = model.parameters().get(3);
        assertEquals(4, fourth.index());
        assertEquals("name", fourth.name());
        assertEquals(ColumnType.VARCHAR, fourth.type());
    }

    @Test
    void shouldProduceExecutableSql() {
        String sql = """
            SELECT id, name
            FROM users
            WHERE id = $1
            """;

        Query query = new Query(
            "GetUser",
            QueryType.ONE,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            schema
        );

        assertEquals(
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(1),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldResolveBindingIndexesInTextualOrder() {
        String sql = """
            SELECT id, name
            FROM users
            WHERE active = $2
              AND id = $1
            """;

        Query query = new Query(
            "FindUser",
            QueryType.ONE,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            schema
        );

        assertEquals(
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                  AND id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(
            List.of(2, 1),
            model.bindingParameterIndexes()
        );
    }

    @Test
    void shouldResolveQuotedIdentifiersWithoutDelimiters() {
        Schema quotedSchema = new Schema(
            List.of(
                new Table(
                    "user data",
                    List.of(
                        new Column("user id", ColumnType.BIGINT, false),
                        new Column("select", ColumnType.VARCHAR, true)
                    ),
                    List.of()
                )
            )
        );

        String sql = """
            SELECT "user id", "select"
            FROM "user data"
            WHERE "select" = $1
            """;

        Query query = new Query(
            "ListUserData",
            QueryType.MANY,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            quotedSchema
        );

        assertEquals("user data", model.table());

        assertEquals(
            List.of(
                new QueryColumn("user id", ColumnType.BIGINT, false),
                new QueryColumn("select", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "select", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(
            """
                SELECT "user id", "select"
                FROM "user data"
                WHERE "select" = ?
                """,
            model.executableSql()
        );
    }

    @Test
    void shouldResolveEmptyBindingIndexesWithoutParameters() {
        String sql = """
            SELECT id, name
            FROM users
            """;

        Query query = new Query(
            "ListUsers",
            QueryType.MANY,
            sql
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(sql),
            schema
        );

        assertTrue(model.bindingParameterIndexes().isEmpty());
    }

    @Test
    void shouldAnalyzeAliasedSingleTableSelect() {
        String sql = """
            SELECT u.id, u.name
            FROM users u
            WHERE u.name = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("GetUser", QueryType.ONE, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    /**
     * An explicit projection alias names the result column, while its type and
     * nullability still come from the schema column.
     */
    @Test
    void shouldNameSelectedColumnAfterItsAlias() {
        String sql = """
            SELECT id AS user_id, name AS "user name"
            FROM users
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("user name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldNameJoinedSelectedColumnsAfterTheirAliases() {
        String sql = """
            SELECT u.id AS user_id, p.id AS profile_id, p.nickname
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("profile_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldRejectTableNameHiddenByAlias() {
        String sql = """
            SELECT users.id
            FROM users u
            """;

        Query query = new Query("GetUser", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertTrue(exception.getMessage().contains("users"));
    }

    @Test
    void shouldAnalyzeSingleInnerJoin() {
        String sql = """
            SELECT u.id, p.id, p.nickname
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("GetUserProfile", QueryType.ONE, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );
    }

    @Test
    void shouldAnalyzeExplicitInnerJoinWithMultipleSources() {
        String sql = """
            SELECT u.name, p.nickname, o.total
            FROM users u
            INNER JOIN profiles p ON p.user_id = u.id
            INNER JOIN orders o ON o.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    /**
     * A left-joined source contributes no row when the join finds no match, so
     * every column projected from it is nullable even when its schema
     * declaration is not. Both accepted spellings are analyzed identically.
     */
    @ParameterizedTest
    @ValueSource(strings = { "LEFT JOIN", "LEFT OUTER JOIN" })
    void shouldAnalyzeLeftJoinWithNullableJoinedColumns(String joinKeywords) {
        String sql = """
            SELECT u.id, p.id AS profile_id, p.nickname
            FROM users u
            %s profiles p ON p.user_id = u.id
            """.formatted(joinKeywords);

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("profile_id", ColumnType.BIGINT, true),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A bare wildcard embeds the row of every table source, each in its written
     * position, and the row of a left-joined source is one nullable component
     * whose own columns keep their declared nullability.
     */
    @Test
    void shouldEmbedTheRowOfALeftJoinedSourceAsNullable() {
        String sql = """
            SELECT *
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                QueryColumn.row(
                    "u",
                    "users",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("name", ColumnType.VARCHAR, true)
                    )
                ),
                QueryColumn.row(
                    "p",
                    true,
                    "profiles",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("user_id", ColumnType.BIGINT, false),
                        new QueryColumn("nickname", ColumnType.VARCHAR, true)
                    )
                )
            ),
            model.columns()
        );
    }

    @Test
    void shouldEmbedTheRowOfAQualifiedLeftJoinedWildcardAsNullable() {
        String sql = """
            SELECT p.*, u.name
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                QueryColumn.row(
                    "p",
                    true,
                    "profiles",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("user_id", ColumnType.BIGINT, false),
                        new QueryColumn("nickname", ColumnType.VARCHAR, true)
                    )
                ),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * Inner and left joins may be chained in either order, and only the
     * left-joined sources become nullable.
     */
    @Test
    void shouldAnalyzeLeftJoinAfterInnerJoin() {
        String sql = """
            SELECT p.id, p.nickname, o.id, o.total
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            LEFT JOIN orders o ON o.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, true),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldAnalyzeInnerJoinAfterLeftJoin() {
        String sql = """
            SELECT p.id, o.id, o.total
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            JOIN orders o ON o.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, true),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    /** A left join changes no parameter and no binding order. */
    @Test
    void shouldResolveLeftJoinedParametersInTextualBindingOrder() {
        String sql = """
            SELECT u.id, p.nickname
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("GetUserProfile", QueryType.ONE, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A bare wildcard over joined sources embeds one row of each source's
     * table, in source order, named after the name each source exposes.
     */
    @Test
    void shouldExpandAllColumnsAcrossJoinedSourcesInOrder() {
        String sql = """
            SELECT *
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(usersRow("u"), profilesRow("p")),
            model.columns()
        );
    }

    /**
     * Each qualified wildcard of a joined read embeds the row of its own
     * source's table, in its written position.
     */
    @Test
    void shouldEmbedTheRowOfEachQualifiedWildcardOfAJoinedRead() {
        String sql = """
            SELECT u.*, p.*
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(usersRow("u"), profilesRow("p")),
            model.columns()
        );
    }

    /**
     * One table read under two aliases embeds that one table's row twice, each
     * component named after its own alias.
     */
    @Test
    void shouldEmbedOneTableRowUnderEachOfItsAliases() {
        String sql = """
            SELECT u.*, parent.*
            FROM users u
            JOIN users parent ON parent.id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserParents", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(usersRow("u"), usersRow("parent")),
            model.columns()
        );
    }

    /**
     * A wildcard embeds its source's row in its written position while every
     * other item stays a flat component of the selected order, a column and an
     * aliased count included.
     */
    @Test
    void shouldExpandQualifiedAllColumnsForOneSource() {
        String sql = """
            SELECT p.*, u.name
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                profilesRow("p"),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldEmbedTheRowOfAWildcardBesideAScalarCount() {
        String sql = """
            SELECT u.*, COUNT(*) AS profiles
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            GROUP BY u.id, u.name
            """;

        QueryModel model = analyzer.analyze(
            new Query("CountUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                usersRow("u"),
                new QueryColumn("profiles", ColumnType.BIGINT, false)
            ),
            model.columns()
        );
    }

    /**
     * A derived source has no row record, so its wildcard stays its own
     * columns, and the wildcard of its body expands there as it does in any
     * analyzed {@code SELECT}.
     */
    @Test
    void shouldExpandTheWildcardOfADerivedSourceAsItsOwnColumns() {
        String sql = """
            WITH recent AS (SELECT * FROM orders)
            SELECT recent.*, u.name
            FROM users u
            JOIN recent ON recent.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListRecentOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("total", ColumnType.DECIMAL, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /** The embedded row of the {@code users} table under one exposed name. */
    private QueryColumn usersRow(String name) {
        return QueryColumn.row(
            name,
            "users",
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            )
        );
    }

    /** The embedded row of the {@code profiles} table under one exposed name. */
    private QueryColumn profilesRow(String name) {
        return QueryColumn.row(
            name,
            "profiles",
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            )
        );
    }

    /**
     * A query whose result is one complete table row carries the schema's own
     * spelling of that table, so every such query shares one row identity
     * regardless of the alias or spelling it used.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT * FROM users",
            "SELECT * FROM Users",
            "SELECT u.* FROM users u",
            "SELECT u.* FROM USERS u"
        }
    )
    void shouldResolveRowTableForFullRowSelect(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals("users", model.rowTable());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id) VALUES ($1) RETURNING *",
            "UPDATE Users SET name = $2 WHERE id = $1 RETURNING *",
            "DELETE FROM users WHERE id = $1 RETURNING *"
        }
    )
    void shouldResolveRowTableForReturningAllColumns(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("WriteUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals("users", model.rowTable());
    }

    /**
     * Every other result shape stays specific to its query, including an
     * explicit list of every column and a wildcard combined with another item.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id, name, active FROM users",
            "SELECT *, id FROM users",
            "SELECT id, * FROM users",
            "INSERT INTO users (id) VALUES ($1) RETURNING id, name, active",
            "INSERT INTO users (id) VALUES ($1) RETURNING id AS a, name AS b, active AS c",
            "DELETE FROM users WHERE id = $1 RETURNING id"
        }
    )
    void shouldNotResolveRowTableForQuerySpecificResult(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ReadUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertNull(model.rowTable());
    }

    /**
     * A bare wildcard of a joined read covers every source, so it returns a
     * result of its own rather than one table's shared row.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT * FROM users u JOIN profiles p ON p.user_id = u.id",
            "SELECT * FROM users u LEFT JOIN profiles p ON p.user_id = u.id"
        }
    )
    void shouldNotResolveRowTableForJoinedWildcard(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());
    }

    /**
     * A lone wildcard of one joined source covers exactly that source, so it
     * returns that table's shared row, in the schema's own spelling and with
     * the row's own columns.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT u.* FROM users u JOIN profiles p ON p.user_id = u.id",
            "SELECT users.* FROM users JOIN profiles p ON p.user_id = users.id"
        }
    )
    void shouldResolveRowTableForALoneJoinedWildcard(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A lone wildcard of a left-joined source may read no row, so it returns a
     * result of its own holding that row as one nullable component rather than
     * the row record itself.
     */
    @Test
    void shouldNotResolveRowTableForALoneLeftJoinedWildcard() {
        String sql = "SELECT p.* FROM users u LEFT JOIN profiles p ON p.user_id = u.id";

        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                QueryColumn.row(
                    "p",
                    true,
                    "profiles",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("user_id", ColumnType.BIGINT, false),
                        new QueryColumn("nickname", ColumnType.VARCHAR, true)
                    )
                )
            ),
            model.columns()
        );
    }

    /**
     * A single-source wildcard written beside another item embeds that table's
     * row in its own position instead of returning the row itself.
     */
    @Test
    void shouldEmbedTheRowOfASingleSourceWildcardBesideAnotherItem() {
        String sql = "SELECT *, id FROM users";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                QueryColumn.row(
                    "users",
                    "users",
                    List.of(
                        new QueryColumn("id", ColumnType.BIGINT, false),
                        new QueryColumn("name", ColumnType.VARCHAR, true),
                        new QueryColumn("active", ColumnType.BOOLEAN, true)
                    )
                ),
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            model.columns()
        );
    }

    @Test
    void shouldNotResolveRowTableForExecWrite() {
        String sql = "DELETE FROM users WHERE id = $1";

        QueryModel model = analyzer.analyze(
            new Query("DeleteUser", QueryType.EXEC, sql),
            parser.parse(sql),
            schema
        );

        assertNull(model.rowTable());
    }

    @Test
    void shouldResolveUniqueUnqualifiedColumnInJoinedQuery() {
        String sql = """
            SELECT nickname, name
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE nickname = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListNicknames", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    @Test
    void shouldResolveJoinedParametersInTextualBindingOrder() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            WHERE p.nickname = $2
              AND u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());
    }

    @Test
    void shouldRejectAmbiguousUnqualifiedColumn() {
        String sql = """
            SELECT id
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertTrue(exception.getMessage().contains("id"));
    }

    @Test
    void shouldRejectUnknownColumnQualifier() {
        String sql = """
            SELECT o.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertTrue(exception.getMessage().contains("o"));
    }

    @Test
    void shouldRejectDuplicateExposedSourceName() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles U ON U.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertTrue(exception.getMessage().contains("U"));
    }

    /**
     * {@link net.sf.jsqlparser.statement.select.Join#isInnerJoin()} also
     * reports these shapes as inner joins, so the supported-shape gate must
     * reject them explicitly.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "FROM users u, profiles p",
            "FROM users u STRAIGHT_JOIN profiles p ON p.user_id = u.id"
        }
    )
    void shouldRejectExcludedJoinReportedAsInnerJoin(String fromClause) {
        String sql = """
            SELECT u.id
            %s
            """.formatted(fromClause);

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );
    }

    /**
     * Only a bare or {@code INNER} join and a {@code LEFT} join in its two
     * spellings are supported, so every other qualifier stays rejected,
     * including the qualifiers that also report {@code isOuter()} or
     * {@code isLeft()}. A join kind that can be written as a supported join
     * names that rewrite, and every other modifier keeps the general
     * rejection.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "RIGHT JOIN profiles p ON p.user_id = u.id"
                + "|RIGHT JOIN is not supported;"
                + " write a LEFT JOIN with the two sources swapped.",
            "RIGHT OUTER JOIN profiles p ON p.user_id = u.id"
                + "|RIGHT JOIN is not supported;"
                + " write a LEFT JOIN with the two sources swapped.",
            "FULL OUTER JOIN profiles p ON p.user_id = u.id"
                + "|FULL JOIN is not supported.",
            "FULL JOIN profiles p ON p.user_id = u.id"
                + "|FULL JOIN is not supported.",
            "CROSS JOIN profiles p"
                + "|CROSS JOIN is not supported; write JOIN ... ON TRUE.",
            "NATURAL JOIN profiles p"
                + "|NATURAL JOIN is not supported; write the join condition with ON.",
            "NATURAL LEFT JOIN profiles p"
                + "|NATURAL JOIN is not supported; write the join condition with ON.",
            "JOIN profiles p USING (user_id)"
                + "|JOIN ... USING is not supported; write the join condition with ON.",
            "LEFT JOIN profiles p USING (user_id)"
                + "|JOIN ... USING is not supported; write the join condition with ON.",
            "OUTER JOIN profiles p ON p.user_id = u.id"
                + "|Only unmodified INNER JOIN and LEFT JOIN clauses are supported.",
            "LEFT SEMI JOIN profiles p ON p.user_id = u.id"
                + "|Only unmodified INNER JOIN and LEFT JOIN clauses are supported."
        }
    )
    void shouldRejectUnsupportedJoinModifier(String joinClause, String message) {
        String sql = """
            SELECT u.id
            FROM users u
            %s
            """.formatted(joinClause);

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * An {@code ON} condition may be any boolean expression, including a
     * composite key, a comparison other than equality, a condition over one
     * source alone, an unqualified column reference, and {@code ON TRUE}.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ON p.user_id = u.id AND p.nickname = u.name",
            "ON p.user_id > u.id",
            "ON p.user_id = p.id",
            "ON user_id = u.id",
            "ON TRUE"
        }
    )
    void shouldAnalyzeJoinConditionOfAnyBooleanExpression(String onClause) {
        String sql = """
            SELECT u.id, p.nickname
            FROM users u
            JOIN profiles p %s
            """.formatted(onClause);

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());
    }

    /**
     * The join kind alone decides nullability, so a filtered left join still
     * reads every column of its joined source as nullable, even one the schema
     * declares {@code NOT NULL}, while the base source keeps its schema
     * nullability.
     */
    @Test
    void shouldReadFilteredLeftJoinedColumnsAsNullable() {
        String sql = """
            SELECT u.id, p.id AS profile_id, p.nickname
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id AND p.nickname IS NOT NULL
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("profile_id", ColumnType.BIGINT, true),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A join condition types its placeholders by the {@code WHERE} rules over
     * its own scope, and every occurrence binds in textual order, so a join
     * placeholder binds before a {@code WHERE} one.
     */
    @Test
    void shouldResolveJoinConditionParameterInTextualBindingOrder() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id AND p.nickname = $2
            WHERE u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());
    }

    @Test
    void shouldResolveNamedJoinConditionParameter() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id AND p.nickname = :nickname
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    /**
     * A cast placeholder in a join condition states its own type and keeps the
     * compared column's name, as it does in a {@code WHERE} comparison.
     */
    @Test
    void shouldTypeCastJoinConditionPlaceholderFromItsCast() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id AND p.nickname = $1::text
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.TEXT)),
            model.parameters()
        );
    }

    /**
     * An {@code ON} condition sees the sources introduced up to and including
     * its own joined source, so a reference to a source joined later is an
     * unknown qualifier.
     */
    @Test
    void shouldRejectJoinConditionReferenceToLaterSource() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = o.user_id
            JOIN orders o ON o.user_id = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Unknown source qualifier in query: o",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnknownJoinConditionColumn() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.missing = u.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Column not found in table profiles: missing",
            exception.getMessage()
        );
    }

    /**
     * Each join carries exactly one {@code ON} condition. The parser reports
     * none for a join written without one, and two for the nested
     * {@code JOIN a JOIN b ON ... ON ...} form.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "JOIN profiles p",
            "LEFT JOIN profiles p",
            "JOIN profiles p JOIN orders o ON o.user_id = u.id ON p.user_id = u.id"
        }
    )
    void shouldRejectJoinWithoutOneOnCondition(String joinClause) {
        String sql = """
            SELECT u.id
            FROM users u
            %s
            """.formatted(joinClause);

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "A join requires one ON condition.",
            exception.getMessage()
        );
    }

    @Test
    void shouldRetainOneParameterForRepeatedIndex() {
        String sql = """
            SELECT *
            FROM users
            WHERE name = $2
              AND (id = $1 OR id = $1)
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1, 1), model.bindingParameterIndexes());

        assertEquals(
            """
                SELECT *
                FROM users
                WHERE name = ?
                  AND (id = ? OR id = ?)
                """,
            model.executableSql()
        );
    }

    @Test
    void shouldRetainFirstOccurrenceOfRepeatedIndexWithSameJavaType() {
        Schema textSchema = new Schema(
            List.of(
                new Table(
                    "users",
                    List.of(
                        new Column("name", ColumnType.VARCHAR, true),
                        new Column("note", ColumnType.TEXT, true)
                    ),
                    List.of()
                )
            )
        );

        String sql = "SELECT name FROM users WHERE name = $1 AND note = $1";

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            textSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    @Test
    void shouldRejectRepeatedIndexWithConflictingType() {
        String sql = "UPDATE users SET name = $1 WHERE id = $1";

        Query query = new Query("UpdateUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder $1 has conflicting types: String from 'name' and Long from 'id'",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectGappedParameterIndexes() {
        String sql = "SELECT * FROM users WHERE id = $1 AND name = $3";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder indexes must start at $1 without gaps, but were [1, 3]",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectZeroParameterIndex() {
        String sql = "SELECT * FROM users WHERE id = $0";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder indexes must start at $1 without gaps, but were [0]",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectPlaceholderInUnsupportedLocation() {
        String sql = "SELECT * FROM users WHERE id = $1 LIMIT $2 + 1";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Cannot infer the type of $2 in $2 + 1;"
                + " state it with a cast, such as $2::text",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectAnonymousParameter() {
        String sql = "SELECT * FROM users WHERE id = ?";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Anonymous '?' parameters are not supported; use an indexed placeholder such as $1",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectAnonymousParameterInUnsupportedLocation() {
        String sql = "SELECT id, name FROM users WHERE NOT (id = ?) AND name = $1";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Anonymous '?' parameters are not supported; use an indexed placeholder such as $1",
            exception.getMessage()
        );
    }

    @Test
    void shouldResolveNamedParameter() {
        String sql = "SELECT * FROM users WHERE id = :userId";

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "userId", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT * FROM users WHERE id = ?",
            model.executableSql()
        );
    }

    /**
     * Each distinct name is one parameter numbered by its first textual
     * occurrence, while every occurrence binds at its own textual position.
     */
    @Test
    void shouldNumberNamedParametersByFirstOccurrence() {
        String sql = """
            SELECT id FROM users
            WHERE (name = :term OR bio = :term)
              AND id > :minId
            ORDER BY id OFFSET :skip LIMIT :pageSize""";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            predicateSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "term", ColumnType.VARCHAR),
                new QueryParameter(2, "minId", ColumnType.BIGINT),
                new QueryParameter(3, "skip", ColumnType.INTEGER),
                new QueryParameter(4, "pageSize", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 1, 2, 3, 4), model.bindingParameterIndexes());
    }

    /**
     * An update names its parameters after its placeholders, and keeps binding
     * its assignments before its predicate.
     */
    @Test
    void shouldResolveNamedParametersOfUpdate() {
        String sql = "UPDATE users SET name = :newName WHERE id = :id";

        QueryModel model = analyzer.analyze(
            new Query("UpdateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "newName", ColumnType.VARCHAR),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "UPDATE users SET name = ? WHERE id = ?",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveNamedParametersOfInsert() {
        String sql = "INSERT INTO users (id, name) VALUES (:id, :name)";

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(
            "INSERT INTO users (id, name) VALUES (?, ?)",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveNamedParametersInInList() {
        String sql = "SELECT id FROM users WHERE id IN (:first, :second)";

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "first", ColumnType.BIGINT),
                new QueryParameter(2, "second", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM users WHERE id IN (?, ?)",
            model.executableSql()
        );
    }

    @Test
    void shouldResolveNamedLikePatternParameter() {
        String sql = "SELECT id FROM users WHERE name LIKE :pattern";

        QueryModel model = analyzer.analyze(
            new Query("SearchUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "pattern", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM users WHERE name LIKE ?",
            model.executableSql()
        );
    }

    /**
     * A placeholder name is the word written after the colon, whatever the SQL
     * parser makes of that word elsewhere, so a name spelled like a keyword is
     * a parameter of its own.
     */
    @Test
    void shouldResolveNamedParametersSpelledLikeKeywords() {
        String sql = "SELECT id FROM users WHERE id = :limit AND name = :user ORDER BY id LIMIT :year";

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "limit", ColumnType.BIGINT),
                new QueryParameter(2, "user", ColumnType.VARCHAR),
                new QueryParameter(3, "year", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM users WHERE id = ? AND name = ? ORDER BY id LIMIT ?",
            model.executableSql()
        );
    }

    @Test
    void shouldRejectMixedPlaceholderForms() {
        String sql = "SELECT * FROM users WHERE id IN ($1, :other)";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Indexed '$N' and named ':name' placeholders must not be mixed in one query",
            exception.getMessage()
        );
    }

    /**
     * A qualified, quoted, or {@code &name} placeholder is rejected wherever it
     * appears, including a clause this analyzer does not traverse and the
     * operand of a cast, because the executable SQL would otherwise keep it.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        value = {
            "SELECT * FROM users WHERE id = :a.b|:a.b",
            "SELECT * FROM users WHERE id = :\"x\"|:\"x\"",
            "SELECT * FROM users WHERE id = &x|&x",
            "SELECT id FROM users WHERE id = :id ORDER BY :\"x\"|:\"x\"",
            "SELECT id FROM users WHERE id = :id ORDER BY &x|&x",
            "SELECT id FROM users WHERE id = abs(:\"x\")|:\"x\"",
            "SELECT id FROM users WHERE id = abs(&x)|&x",
            "SELECT id FROM users WHERE id = abs(:a.b)|:a.b",
            "SELECT id FROM users WHERE id = :\"x\"::bigint|:\"x\"",
            "SELECT id FROM users WHERE id = &x::bigint|&x",
            "SELECT id FROM users WHERE id = :a.b::bigint|:a.b",
            "SELECT id FROM users WHERE id = :\"x\"::int::bigint|:\"x\"",
            "SELECT id FROM users WHERE id = :id ORDER BY &x::int|&x"
        }
    )
    void shouldRejectUnsupportedNamedPlaceholderForm(String sql, String placeholder) {
        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Named placeholder '%s' is not supported; write an unquoted name of letters, digits, "
                .formatted(placeholder)
                + "and underscores directly after ':', such as :userId",
            exception.getMessage()
        );
    }

    /**
     * A named placeholder in a location this analyzer does not visit is
     * rejected as an untyped placeholder, which writes the placeholder and the
     * expression it stands in as the query does.
     */
    @Test
    void shouldRejectNamedPlaceholderInUnanalyzedLocation() {
        String sql = "SELECT id FROM users WHERE lower(name) = :name";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Cannot infer the type of :name in lower(name) = :name;"
                + " state it with a cast, such as :name::text",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectNamedPlaceholderWithConflictingTypes() {
        String sql = "SELECT id FROM users WHERE id = :value AND name = :value";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder :value has conflicting types: Long and String",
            exception.getMessage()
        );
    }

    /**
     * A cast types the placeholder it casts wherever that placeholder appears
     * inside an analyzed clause, so the optional filter idiom is one parameter
     * of the cast's type: the occurrence under the cast and the compared
     * occurrence are one named placeholder.
     */
    @Test
    void shouldResolveNamedCastParameterOfOptionalFilter() {
        String sql = "SELECT id FROM users WHERE (:name::text IS NULL OR name = :name)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE (?::text IS NULL OR name = ?)",
            model.executableSql()
        );
    }

    /**
     * A computed {@code LIKE} pattern is not analyzed as a pattern, but the
     * cast placeholder inside it is typed by its cast and named after itself.
     */
    @Test
    void shouldResolveNamedCastParameterInsideComputedLikePattern() {
        String sql = "SELECT id FROM users WHERE name LIKE '%' || :term::text || '%'";

        QueryModel model = analyzer.analyze(
            new Query("SearchUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "term", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE name LIKE '%' || ?::text || '%'",
            model.executableSql()
        );
    }

    /**
     * A cast placeholder inside a computed assignment is typed by its cast, and
     * the parameters stay in first-occurrence order with the assignment bound
     * before the predicate.
     */
    @Test
    void shouldResolveNamedCastParameterInsideUpdateAssignment() {
        String sql = "UPDATE users SET bio = COALESCE(:bio::text, bio) WHERE id = :id";

        QueryModel model = analyzer.analyze(
            new Query("UpdateUserBio", QueryType.EXEC, sql),
            parser.parse(sql),
            predicateSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "bio", ColumnType.TEXT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "UPDATE users SET bio = COALESCE(?::text, bio) WHERE id = ?",
            model.executableSql()
        );
    }

    /**
     * The {@code CAST} spelling types a placeholder like {@code ::} does, and a
     * cast placeholder compared with a column keeps that column's name.
     */
    @Test
    void shouldResolveCastKeywordParameterNamedAfterItsComparedColumn() {
        String sql = "SELECT id FROM users WHERE id = CAST($1 AS bigint)";

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM users WHERE id = CAST(? AS bigint)",
            model.executableSql()
        );
    }

    /**
     * A cast placeholder that is the direct value of a location that names an
     * uncast placeholder after its column keeps that column's name and takes
     * the cast's type.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users WHERE name = $1::text",
            "SELECT id FROM users WHERE $1::text = name",
            "SELECT id FROM users WHERE name <> $1::text",
            "SELECT id FROM users WHERE name IN ($1::text, 'a')",
            "SELECT id FROM users WHERE name BETWEEN $1::text AND 'z'",
            "SELECT id FROM users WHERE name LIKE $1::text"
        }
    )
    void shouldNameCastParameterAfterItsColumn(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A cast pattern is accepted in a pattern shape that rejects an uncast
     * pattern placeholder, but no column names it there, so it is named after
     * its own index while keeping the cast's type and its binding position.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users WHERE name SIMILAR TO $1::text",
            "SELECT id FROM users WHERE name NOT LIKE $1::text",
            "SELECT id FROM users WHERE name NOT ILIKE $1::text",
            "SELECT id FROM users WHERE name LIKE $1::text ESCAPE '!'"
        }
    )
    void shouldNameCastPatternAfterItsPlaceholderInAnUntypedPatternShape(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "param1", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /** A named cast pattern keeps its own name in every pattern shape. */
    @Test
    void shouldNameNamedCastPatternAfterItsPlaceholder() {
        String sql = "SELECT id FROM users WHERE name NOT LIKE :p::text";

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "p", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());

        assertEquals(
            "SELECT id FROM users WHERE name NOT LIKE ?::text",
            model.executableSql()
        );
    }

    /** A cast may name a declared enum type, which types its placeholder. */
    @Test
    void shouldResolveEnumCastParameter() {
        String sql = "SELECT id FROM stages WHERE setting = $1::stage_setting";

        QueryModel model = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, sql),
            parser.parse(sql),
            enumSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting")
            ),
            model.parameters()
        );
    }

    /**
     * A cast of a one-dimensional array types its placeholder as a list of the
     * element type. The array overlap operator is not one of the supported
     * comparisons, so its placeholder is named after itself rather than after
     * the column.
     */
    @Test
    void shouldResolveArrayCastParameterNamedAfterItsPlaceholder() {
        String sql = "SELECT id FROM stages WHERE tags && $1::varchar[]";

        QueryModel model = analyzer.analyze(
            new Query("ListStagesByTags", QueryType.MANY, sql),
            parser.parse(sql),
            arraySchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "param1", ColumnType.VARCHAR, null, true)
            ),
            model.parameters()
        );

        assertEquals(
            "SELECT id FROM stages WHERE tags && ?::varchar[]",
            model.executableSql()
        );
    }

    /** A blank-padded character cast keeps PostgreSQL's own name of the type. */
    @Test
    void shouldResolveBlankPaddedCastParameter() {
        String sql = "SELECT id FROM users WHERE bio = $1::char (3)";

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            predicateSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "bio", ColumnType.VARCHAR, null, false, true)
            ),
            model.parameters()
        );
    }

    /**
     * An indexed cast placeholder in a location that names no column is named
     * after its own index, and a later occurrence of the index keeps that name
     * and type.
     */
    @Test
    void shouldNameIndexedCastParameterAfterItsIndex() {
        String sql = "SELECT id FROM users WHERE ($1::text IS NULL OR name = $1)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "param1", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    /**
     * An inserted value that is a cast placeholder keeps its column's name,
     * while a cast placeholder inside a computed value is named after itself.
     */
    @Test
    void shouldResolveCastParametersOfInsertValues() {
        String sql = "INSERT INTO users (id, bio) VALUES ($1::bigint, COALESCE($2::text, ''))";

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "param2", ColumnType.TEXT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A cast type sqlcj does not map, including an array of more than one
     * dimension, is rejected naming the placeholder it would have typed and
     * the declared type.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "SELECT id FROM users WHERE id = $1::interval|$1|INTERVAL",
            "SELECT id FROM users WHERE id = $1::int[][]|$1|INT[][]",
            "SELECT id FROM users WHERE id = :value::interval|:value|INTERVAL"
        }
    )
    void shouldRejectUnsupportedCastType(String sql, String placeholder, String type) {
        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder %s has unsupported cast type %s".formatted(placeholder, type),
            exception.getMessage()
        );
    }

    /**
     * One placeholder whose cast occurrence and column occurrence resolve to
     * different Java types is rejected like any other conflicting placeholder.
     */
    @Test
    void shouldRejectCastOccurrenceWithConflictingType() {
        String sql = "SELECT id FROM users WHERE (:value::text IS NULL OR id = :value)";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder :value has conflicting types: String and Long",
            exception.getMessage()
        );
    }

    /**
     * A cast types a placeholder in a clause this analyzer does not otherwise
     * traverse, which is how a dynamic sort states its type. The sort
     * placeholder is repeated, so it is one logical parameter bound at both of
     * its positions, after the predicate parameter.
     */
    @Test
    void shouldResolveCastPlaceholdersOfOrderBy() {
        String sql = """
            SELECT id, name
            FROM users
            WHERE id = :projectId
            ORDER BY
              CASE WHEN :sort::text = 'name' THEN name END,
              CASE WHEN :sort::text = 'id' THEN id END""";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "projectId", ColumnType.BIGINT),
                new QueryParameter(2, "sort", ColumnType.TEXT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 2), model.bindingParameterIndexes());

        assertEquals(
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                ORDER BY
                  CASE WHEN ?::text = 'name' THEN name END,
                  CASE WHEN ?::text = 'id' THEN id END""",
            model.executableSql()
        );
    }

    /**
     * An indexed cast placeholder outside the clauses that name a parameter is
     * named after its own index, in the {@code ::} and the {@code CAST} form
     * alike.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users WHERE id = $1 ORDER BY $2::int",
            "SELECT id FROM users WHERE id = $1 ORDER BY CAST($2 AS int)"
        }
    )
    void shouldNameIndexedCastPlaceholderOfOrderByAfterItsIndex(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "param2", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A cast types a placeholder in every other clause of a read: the grouping
     * list, the group predicate, and a computed pagination value, which is not
     * the whole row count and is therefore named after its own placeholder.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT name FROM users GROUP BY name, :sort::text|sort|TEXT",
            "SELECT name FROM users GROUP BY name HAVING COUNT(*) > :minimum::bigint"
                + "|minimum|BIGINT",
            "SELECT id FROM users ORDER BY id LIMIT LEAST(:pageSize::int, 100)"
                + "|pageSize|INTEGER",
            "SELECT id FROM users ORDER BY id FETCH FIRST :pageSize::int ROWS ONLY"
                + "|pageSize|INTEGER",
            "SELECT id FROM users ORDER BY id LIMIT LEAST($1::int, 100)|param1|INTEGER",
            "SELECT id FROM users ORDER BY id FETCH FIRST $1::int ROWS ONLY|param1|INTEGER"
        }
    )
    void shouldResolveCastPlaceholderOfEveryReadClause(String sql, String name, ColumnType type) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, name, type)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * An indexed cast placeholder of a clause that names no parameter is named
     * after its own index wherever it stands.
     */
    @Test
    void shouldNameIndexedCastPlaceholderOfGroupPredicateAfterItsIndex() {
        String sql = "SELECT name, COUNT(*) AS total FROM users GROUP BY name HAVING COUNT(*) > $1::bigint";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "param1", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A cast types a placeholder inside a predicate subquery at any depth, in a
     * read and in a write, instead of the column its clause compares it with.
     * Such a placeholder keeps its own name, as a named cast placeholder does
     * in every clause.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users"
                + " WHERE EXISTS (SELECT 1 FROM orders WHERE orders.total = :amount::numeric)"
                + "|amount",
            "SELECT id FROM users"
                + " WHERE id IN (SELECT user_id FROM orders WHERE orders.total = :amount::numeric)"
                + "|amount",
            "SELECT id FROM users WHERE id ="
                + " (SELECT MAX(user_id) FROM orders WHERE orders.total = :amount::numeric)"
                + "|amount",
            "SELECT id FROM users WHERE EXISTS ("
                + "SELECT 1 FROM orders"
                + " WHERE EXISTS (SELECT 1 FROM profiles WHERE profiles.nickname = :amount::text))"
                + "|amount",
            "DELETE FROM users"
                + " WHERE id IN (SELECT user_id FROM orders WHERE orders.total = :amount::numeric)"
                + "|amount"
        }
    )
    void shouldResolveCastPlaceholderOfSubquery(String sql, String name) {
        QueryType type = sql.startsWith("DELETE")
            ? QueryType.EXEC
            : QueryType.MANY;

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", type, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(1, model.parameters().size());
        assertEquals(name, model.parameters().get(0).name());
        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A {@code WITH} body is analyzed by its own clause rules, so its cast
     * placeholder is typed by that cast, named after the column it is compared
     * with, and binds before the clauses that follow it.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "WITH recent AS (SELECT id FROM orders WHERE orders.total = $1::numeric)"
                + " SELECT id FROM users WHERE id = $2|MANY",
            "WITH recent AS (SELECT id FROM orders WHERE orders.total = $1::numeric)"
                + " DELETE FROM users WHERE id = $2|EXEC"
        }
    )
    void shouldResolveCastPlaceholderOfWithBody(String sql, QueryType type) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", type, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "total", ColumnType.DECIMAL),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * Two occurrences of one placeholder whose casts agree are one logical
     * parameter, even in different clauses.
     */
    @Test
    void shouldShareOneParameterBetweenAgreeingCastsOfDifferentClauses() {
        String sql = "SELECT id FROM users WHERE name = :term::text"
            + " ORDER BY CASE WHEN :term::text = 'name' THEN name END";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryParameter(1, "term", ColumnType.TEXT)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    /**
     * Two occurrences of one placeholder whose casts state different Java types
     * are rejected like any other conflicting placeholder.
     */
    @Test
    void shouldRejectConflictingCastsOfDifferentClauses() {
        String sql = "SELECT id FROM users WHERE name = :term::text"
            + " ORDER BY CASE WHEN :term::int = 1 THEN name END";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder :term has conflicting types: String and Integer",
            exception.getMessage()
        );
    }

    /**
     * Only a placeholder a cast states the type of is typed outside the clauses
     * that name a parameter, so an uncast placeholder, and a placeholder a cast
     * does not cast directly, are rejected as untyped, each named with the
     * expression or clause it stands in. A subquery this subset does not
     * analyze, such as the query of an {@code = ANY}, types no placeholder of
     * its own clauses either.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users ORDER BY $1|$1|ORDER BY",
            "SELECT id FROM users ORDER BY CASE WHEN :sort = 'name' THEN name END"
                + "|:sort|:sort = 'name'",
            "SELECT id FROM users ORDER BY (:sort)::int|:sort|(:sort)::int",
            "SELECT id FROM users"
                + " WHERE id = ANY (SELECT user_id FROM orders WHERE orders.id = :id)"
                + "|:id|orders.id = :id"
        }
    )
    void shouldRejectUncastPlaceholderOutsideTheAnalyzedClauses(
        String sql,
        String placeholder,
        String enclosing
    ) {
        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Cannot infer the type of %s in %s; state it with a cast, such as %s::text"
                .formatted(placeholder, enclosing, placeholder),
            exception.getMessage()
        );
    }

    /**
     * A cast type sqlcj does not map is rejected naming the placeholder also
     * where the placeholder stands outside the clauses that name a parameter.
     */
    @Test
    void shouldRejectUnsupportedCastTypeOutsideTheAnalyzedClauses() {
        String sql = "SELECT id FROM users ORDER BY $1::interval";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Placeholder $1 has unsupported cast type INTERVAL",
            exception.getMessage()
        );
    }

    /**
     * A placeholder no clause types and no cast states the type of is rejected
     * naming it as the query spells it, naming where the query writes it, and
     * stating the cast that types it, in either placeholder form. Where it
     * stands is the smallest expression written around it, and the keyword of
     * its clause where the query writes no expression around it.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users WHERE email = lower(:email)|:email|lower(:email)",
            "SELECT id FROM users WHERE email = lower($1)|$1|lower($1)",
            "SELECT id FROM users WHERE metadata->>'k' = :v|:v|metadata->>'k' = :v",
            "SELECT id FROM users ORDER BY :sort|:sort|ORDER BY",
            "SELECT id FROM users ORDER BY name, $1|$1|ORDER BY",
            "SELECT id FROM users LIMIT :n + 1|:n|:n + 1",
            "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM users u WHERE lower(u.email) = :x)"
                + "|:x|lower(u.email) = :x",
            "SELECT $1 AS x FROM users|$1|SELECT"
        }
    )
    void shouldRejectAnUntypedPlaceholder(String sql, String placeholder, String enclosing) {
        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, untypedSchema)
        );

        assertEquals(
            "Cannot infer the type of %s in %s; state it with a cast, such as %s::text"
                .formatted(placeholder, enclosing, placeholder),
            exception.getMessage()
        );
    }

    /**
     * A placeholder of a concatenated pattern stands in the whole {@code LIKE}
     * predicate, which is the smallest expression the query writes around it.
     */
    @Test
    void shouldRejectAnUntypedPlaceholderOfAConcatenatedPattern() {
        String sql = "SELECT id FROM users WHERE name LIKE '%' || :q || '%'";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, untypedSchema)
        );

        assertEquals(
            "Cannot infer the type of :q in name LIKE '%' || :q || '%';"
                + " state it with a cast, such as :q::text",
            exception.getMessage()
        );
    }

    /**
     * One query reports one untyped placeholder, which is the first in textual
     * order, so a repaired query reports the next one.
     */
    @Test
    void shouldRejectTheFirstUntypedPlaceholderOfAQuery() {
        String sql = "SELECT id FROM users WHERE lower(name) = :a AND lower(email) = :b";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, untypedSchema)
        );

        assertEquals(
            "Cannot infer the type of :a in lower(name) = :a;"
                + " state it with a cast, such as :a::text",
            exception.getMessage()
        );
    }

    @Test
    void shouldAnalyzeSelectDeclaredAsOptional() {
        String sql = "SELECT id, name FROM users WHERE id = $1";

        QueryModel model = analyzer.analyze(
            new Query("FindUser", QueryType.OPTIONAL, sql),
            parser.parse(sql),
            schema
        );

        assertEquals("FindUser", model.name());
        assertEquals(QueryType.OPTIONAL, model.type());
        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );
    }

    @Test
    void shouldRejectSelectWithoutResultQueryType() {
        String sql = "SELECT id FROM users WHERE id = $1";

        Query query = new Query("GetUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "SELECT queries must be declared as :one, :optional, or :many",
            exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id) VALUES ($1) RETURNING id",
            "UPDATE users SET name = $2 WHERE id = $1 RETURNING id",
            "DELETE FROM users WHERE id = $1 RETURNING id"
        }
    )
    void shouldRejectReturningWriteDeclaredAsExec(String sql) {
        Query query = new Query("WriteUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Write queries with RETURNING must be declared as :one, :optional, or :many",
            exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id) VALUES ($1) RETURNING id",
            "UPDATE users SET name = $2 WHERE id = $1 RETURNING id",
            "DELETE FROM users WHERE id = $1 RETURNING id"
        }
    )
    void shouldAnalyzeReturningWriteForEveryResultQueryType(String sql) {
        for (QueryType type : List.of(QueryType.ONE, QueryType.OPTIONAL, QueryType.MANY)) {
            QueryModel model = analyzer.analyze(
                new Query("WriteUser", type, sql),
                parser.parse(sql),
                schema
            );

            assertEquals(type, model.type());
            assertEquals("users", model.table());

            assertEquals(
                List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
                model.columns()
            );
        }
    }

    @Test
    void shouldAnalyzeInsertReturningColumnsInDeclaredOrder() {
        Query query = new Query(
            "InsertUser",
            QueryType.ONE,
            """
                INSERT INTO users (id, name)
                VALUES ($1, $2)
                RETURNING name, id
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals(QueryType.ONE, model.type());
        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            model.columns()
        );

        assertEquals(
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                RETURNING name, id
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    @Test
    void shouldExpandInsertReturningAllColumnsInSchemaOrder() {
        Query query = new Query(
            "InsertUser",
            QueryType.ONE,
            "INSERT INTO users (id) VALUES ($1) RETURNING *"
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("active", ColumnType.BOOLEAN, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldAnalyzeUpdateReturningWithRepeatedAndOutOfOrderParameters() {
        Query query = new Query(
            "UpdateUser",
            QueryType.ONE,
            """
                UPDATE users
                SET name = $2,
                    active = $3
                WHERE id = $1
                  AND name = $2
                RETURNING active, id, name
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("active", ColumnType.BOOLEAN, true),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 3, 1, 2), model.bindingParameterIndexes());
    }

    @Test
    void shouldAnalyzeDeleteReturningAllColumns() {
        Query query = new Query(
            "DeleteUsers",
            QueryType.MANY,
            "DELETE FROM users WHERE active = $1 RETURNING *"
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals(QueryType.MANY, model.type());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("active", ColumnType.BOOLEAN, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "active", ColumnType.BOOLEAN)),
            model.parameters()
        );
    }

    /**
     * An alias on a returned column names its result component, as on a
     * projected column, while its type and nullability stay the schema
     * column's and the executed SQL keeps the alias.
     */
    @Test
    void shouldNameInsertReturningColumnsFromTheirAliases() {
        Query query = new Query(
            "InsertUser",
            QueryType.ONE,
            """
                INSERT INTO users (id, name)
                VALUES ($1, $2)
                RETURNING id AS user_id, name
                """
        );

        QueryModel model = analyzer.analyze(
            query,
            parser.parse(query.sql()),
            schema
        );

        assertEquals(
            List.of(
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertNull(model.rowTable());

        assertEquals(
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                RETURNING id AS user_id, name
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    @Test
    void shouldNameUpdateReturningColumnFromABareAlias() {
        String sql = "UPDATE users SET name = $2 WHERE id = $1 RETURNING id new_id";

        QueryModel model = analyzer.analyze(
            new Query("UpdateUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryColumn("new_id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());
    }

    @Test
    void shouldNameDeleteReturningColumnQualifiedThroughTheTargetAlias() {
        String sql = "DELETE FROM users u WHERE u.id = $1 RETURNING u.id AS uid";

        QueryModel model = analyzer.analyze(
            new Query("DeleteUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryColumn("uid", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertNull(model.rowTable());
    }

    @Test
    void shouldNameReturningColumnFromAQuotedAlias() {
        String sql = """
            INSERT INTO users (id) VALUES ($1) RETURNING id AS "User Id"
            """;

        QueryModel model = analyzer.analyze(
            new Query("InsertUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(new QueryColumn("User Id", ColumnType.BIGINT, false)),
            model.columns()
        );
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "INSERT INTO users (id) VALUES ($1) RETURNING * AS x"
                + "|RETURNING * must not be aliased.",
            "INSERT INTO users (id) VALUES ($1) RETURNING * x"
                + "|RETURNING * must not be aliased.",
            "INSERT INTO users (id) VALUES ($1) RETURNING id + 1"
                + "|Unsupported RETURNING item id + 1;"
                + " return columns of the target table or *",
            "INSERT INTO users (id) VALUES ($1) RETURNING id + 1 AS x"
                + "|Unsupported RETURNING item id + 1;"
                + " return columns of the target table or *",
            "INSERT INTO users (id) VALUES ($1) RETURNING users.* AS x"
                + "|RETURNING users.* must not be aliased.",
            "DELETE FROM users WHERE id = $1 RETURNING count(id)"
                + "|Unsupported RETURNING item count(id);"
                + " return columns of the target table or *",
            "DELETE FROM users WHERE id = $1 RETURNING lower(name)"
                + "|Unsupported RETURNING item lower(name);"
                + " return columns of the target table or *"
        }
    )
    void shouldRejectUnsupportedReturningItem(String sql, String message) {
        Query query = new Query("WriteUser", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(message, exception.getMessage());
    }

    @Test
    void shouldRejectUnknownReturningColumn() {
        String sql = "INSERT INTO users (id) VALUES ($1) RETURNING missing";

        Query query = new Query("InsertUser", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Column not found in sources users: missing",
            exception.getMessage()
        );
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "UPDATE users, profiles SET name = $2 WHERE users.id = $1 RETURNING id"
                + "|UPDATE of several tables is not supported;"
                + " write the other sources in UPDATE ... FROM.",
            "UPDATE users u JOIN profiles p ON p.user_id = u.id SET name = $2 RETURNING u.id"
                + "|UPDATE of several tables is not supported;"
                + " write the other sources in UPDATE ... FROM.",
            "DELETE users FROM users JOIN profiles ON profiles.user_id = users.id"
                + " WHERE users.id = $1 RETURNING id"
                + "|A joined DELETE is not supported;"
                + " write the other sources in DELETE ... USING."
        }
    )
    void shouldRejectExcludedReturningWriteForm(String sql, String message) {
        Query query = new Query("WriteUser", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(message, exception.getMessage());
    }

    @Test
    void shouldKeepPlaceholderTextThatIsNotAParameter() {
        String sql = """
            SELECT id, name
            FROM users -- keep $9
            WHERE name = '$1 literal'
              AND id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("FindUsers", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            """
                SELECT id, name
                FROM users -- keep $9
                WHERE name = '$1 literal'
                  AND id = ?
                """,
            model.executableSql()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );
    }

    /**
     * A column the schema recorded without a mapped type fails the query that
     * reads, binds, or expands it, naming the column and the recorded type.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "SELECT tags FROM users|MANY",
            "SELECT * FROM users|MANY",
            "SELECT b.* FROM users b|MANY",
            "SELECT id FROM users WHERE tags = $1|MANY",
            "SELECT id FROM users WHERE tags IN ($1)|MANY",
            "SELECT id FROM users WHERE tags LIKE $1|MANY",
            "SELECT id FROM users WHERE tags = ANY($1)|MANY",
            "INSERT INTO users (tags) VALUES ($1)|EXEC",
            "UPDATE users SET tags = $1|EXEC",
            "DELETE FROM users WHERE id = $1 RETURNING tags|MANY",
            "DELETE FROM users WHERE id = $1 RETURNING *|MANY"
        }
    )
    void shouldRejectQueryThatUsesAnUnsupportedTypeColumn(String sql, QueryType type) {
        Query query = new Query("UseTags", type, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, unsupportedTypeSchema)
        );

        assertEquals(
            "Column 'tags' has unsupported type JSONB[]",
            exception.getMessage()
        );
    }

    /**
     * A query that neither reads, binds, nor expands the recorded column is
     * analyzed exactly as it is over a table without that column. Both schemas
     * declare the same {@code id} and {@code name} columns, and neither query
     * references any other column.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id, name FROM users WHERE id = $1",
            "SELECT COUNT(*) AS total FROM users"
        }
    )
    void shouldAnalyzeQueryBesideAnUnsupportedTypeColumn(String sql) {
        Query query = new Query("ReadUsers", QueryType.MANY, sql);

        assertEquals(
            analyzer.analyze(query, parser.parse(sql), schema),
            analyzer.analyze(query, parser.parse(sql), unsupportedTypeSchema)
        );
    }

    /**
     * {@code IS NULL} consumes no column type, so it resolves the recorded
     * column without failing.
     */
    @Test
    void shouldAnalyzeIsNullOnAnUnsupportedTypeColumn() {
        String sql = "SELECT id FROM users WHERE tags IS NULL";

        QueryModel model = analyzer.analyze(
            new Query("ListUntagged", QueryType.MANY, sql),
            parser.parse(sql),
            unsupportedTypeSchema
        );

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertTrue(model.parameters().isEmpty());

        assertEquals(
            "SELECT id FROM users WHERE tags IS NULL",
            model.executableSql()
        );
    }

    /**
     * A selected enum column, an enum column returned by a write, and a
     * placeholder typed from one carry the enum type the schema declared, so
     * generation can name the Java enum of that type.
     */
    @Test
    void shouldCarryTheEnumTypeOfASelectedColumnAndItsParameter() {
        String sql = "SELECT setting, handle FROM stages WHERE setting = $1";

        QueryModel model = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, sql),
            parser.parse(sql),
            enumSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("setting", ColumnType.ENUM, false, "stage_setting"),
                new QueryColumn("handle", ColumnType.TEXT, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting")),
            model.parameters()
        );
    }

    @Test
    void shouldCarryTheEnumTypeOfAReturningColumnAndItsParameter() {
        String sql = "INSERT INTO stages (id, setting) VALUES ($1, $2) RETURNING setting";

        QueryModel model = analyzer.analyze(
            new Query("CreateStage", QueryType.ONE, sql),
            parser.parse(sql),
            enumSchema
        );

        assertEquals(
            List.of(new QueryColumn("setting", ColumnType.ENUM, false, "stage_setting")),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "setting", ColumnType.ENUM, "stage_setting")
            ),
            model.parameters()
        );
    }

    /**
     * A repeated placeholder index must resolve to one Java type, and each
     * enum type generates a Java type of its own, so an index shared by an enum
     * and a text column, or by two enum types, is rejected. An enum is named by
     * its PostgreSQL type, because analysis renders no Java name.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "UPDATE stages SET setting = $1 WHERE handle = $1|"
                + "Placeholder $1 has conflicting types: stage_setting from 'setting' and String from 'handle'",
            "UPDATE stages SET handle = $1 WHERE setting = $1|"
                + "Placeholder $1 has conflicting types: String from 'handle' and stage_setting from 'setting'",
            "UPDATE stages SET setting = $1 WHERE state = $1|"
                + "Placeholder $1 has conflicting types: stage_setting from 'setting' and shelf_state from 'state'"
        }
    )
    void shouldRejectARepeatedIndexAcrossConflictingEnumTypes(String sql, String message) {
        Query query = new Query("UpdateStage", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, enumSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * An array column carries its element type and its array shape into every
     * selected column and into every parameter typed from it, in a comparison,
     * an {@code IN} list, and a range bound alike.
     */
    @Test
    void shouldCarryTheArrayShapeOfSelectedColumnsAndTheirParameters() {
        String sql = """
            SELECT tags, past_settings, handle
            FROM stages
            WHERE tags = $1
              AND past_settings IN ($2)
              AND scores BETWEEN $3 AND $4""";

        QueryModel model = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, sql),
            parser.parse(sql),
            arraySchema
        );

        assertEquals(
            List.of(
                new QueryColumn("tags", ColumnType.VARCHAR, true, null, true),
                new QueryColumn("past_settings", ColumnType.ENUM, true, "stage_setting", true),
                new QueryColumn("handle", ColumnType.TEXT, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "tags", ColumnType.VARCHAR, null, true),
                new QueryParameter(2, "past_settings", ColumnType.ENUM, "stage_setting", true),
                new QueryParameter(3, "scores", ColumnType.INTEGER, null, true),
                new QueryParameter(4, "scores", ColumnType.INTEGER, null, true)
            ),
            model.parameters()
        );
    }

    /**
     * A parameter typed from a blank-padded character array column carries that
     * spelling, so that generation can bind its elements as {@code bpchar}, and
     * a parameter typed from a varying one does not.
     */
    @Test
    void shouldCarryTheBlankPaddedSpellingOfAnArrayParameter() {
        Schema schema = new Schema(
            List.of(
                new Table(
                    "stages",
                    List.of(
                        new Column("marks", ColumnType.VARCHAR, true, null, null, true, true),
                        new Column("tags", ColumnType.VARCHAR, true, null, null, true)
                    ),
                    List.of()
                )
            ),
            List.of()
        );

        String sql = """
            SELECT marks
            FROM stages
            WHERE marks = $1
              AND tags = $2""";

        QueryModel model = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, sql),
            parser.parse(sql),
            schema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "marks", ColumnType.VARCHAR, null, true, true),
                new QueryParameter(2, "tags", ColumnType.VARCHAR, null, true)
            ),
            model.parameters()
        );
    }

    /** A wildcard expands an array column with its array shape. */
    @Test
    void shouldExpandTheArrayColumnsOfAWildcard() {
        String sql = "SELECT * FROM stages";

        QueryModel model = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, sql),
            parser.parse(sql),
            arraySchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("tags", ColumnType.VARCHAR, true, null, true),
                new QueryColumn("scores", ColumnType.INTEGER, false, null, true),
                new QueryColumn("past_settings", ColumnType.ENUM, true, "stage_setting", true),
                new QueryColumn("setting", ColumnType.ENUM, false, "stage_setting"),
                new QueryColumn("handle", ColumnType.TEXT, true)
            ),
            model.columns()
        );
    }

    /**
     * An {@code INSERT} value and an {@code UPDATE} assignment take the array
     * shape of the column they write, and a {@code RETURNING} item and a
     * {@code RETURNING *} carry it back.
     */
    @Test
    void shouldCarryTheArrayShapeOfAReturningWrite() {
        String insert = "INSERT INTO stages (id, tags) VALUES ($1, $2) RETURNING tags";

        QueryModel inserted = analyzer.analyze(
            new Query("CreateStage", QueryType.ONE, insert),
            parser.parse(insert),
            arraySchema
        );

        assertEquals(
            List.of(new QueryColumn("tags", ColumnType.VARCHAR, true, null, true)),
            inserted.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "tags", ColumnType.VARCHAR, null, true)
            ),
            inserted.parameters()
        );

        String update = "UPDATE stages SET past_settings = $1 WHERE id = $2 RETURNING *";

        QueryModel updated = analyzer.analyze(
            new Query("UpdateStage", QueryType.ONE, update),
            parser.parse(update),
            arraySchema
        );

        assertEquals(
            List.of(
                new QueryParameter(
                    1,
                    "past_settings",
                    ColumnType.ENUM,
                    "stage_setting",
                    true
                ),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            updated.parameters()
        );

        assertEquals(
            new QueryColumn("past_settings", ColumnType.ENUM, true, "stage_setting", true),
            updated.columns().get(3)
        );
    }

    /**
     * An array parameter is a list of its element's Java type, so a placeholder
     * index shared by an array and a value of its element type has no single
     * Java type and is rejected.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "UPDATE stages SET tags = $1 WHERE handle = $1|"
                + "Placeholder $1 has conflicting types: List<String> from 'tags' and String from 'handle'",
            "UPDATE stages SET past_settings = $1 WHERE setting = $1|"
                + "Placeholder $1 has conflicting types: stage_setting[] from 'past_settings' and stage_setting from 'setting'"
        }
    )
    void shouldRejectARepeatedIndexAcrossAnArrayAndItsElement(String sql, String message) {
        Query query = new Query("UpdateStage", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, arraySchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A {@code LIKE} pattern takes the tested column's type, and a list of text
     * is no pattern, so an array column is rejected like any other non-text
     * column.
     */
    @Test
    void shouldRejectLikeOnAnArrayColumn() {
        String sql = "SELECT id FROM stages WHERE tags LIKE $1";

        Query query = new Query("FindStages", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, arraySchema)
        );

        assertEquals(
            "A LIKE pattern placeholder requires a VARCHAR or TEXT column, but tags is VARCHAR[].",
            exception.getMessage()
        );
    }

    /**
     * A reference written with a subscript or a slice projects one element or
     * one range of an array column rather than the column, so it is rejected
     * like any other computed projection, qualified or not.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT tags[1] AS first FROM stages|tags[1]",
            "SELECT tags[1:2] AS s FROM stages|tags[1 : 2]",
            "SELECT s.tags[1] FROM stages s|s.tags[1]"
        }
    )
    void shouldRejectASubscriptedProjection(String sql, String written) {
        Query query = new Query("ListStages", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, arraySchema)
        );

        assertEquals(
            "Cannot infer the type of %s in SELECT;"
                .formatted(written)
                + " state it with a cast and an alias, such as SUM(amount)::numeric AS total",
            exception.getMessage()
        );
    }

    /**
     * A cast states the type of a subscripted projection, as it states the type
     * of any other computed one: an element cast is a scalar column, and a slice
     * cast of an array type is an array column.
     */
    @Test
    void shouldAnalyzeACastSubscriptedProjection() {
        String element = "SELECT tags[1]::text AS first FROM stages";

        QueryModel elementModel = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, element),
            parser.parse(element),
            arraySchema
        );

        assertEquals(
            List.of(new QueryColumn("first", ColumnType.TEXT, true)),
            elementModel.columns()
        );

        String slice = "SELECT tags[1:2]::varchar[] AS s FROM stages";

        QueryModel sliceModel = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, slice),
            parser.parse(slice),
            arraySchema
        );

        assertEquals(
            List.of(new QueryColumn("s", ColumnType.VARCHAR, true, null, true)),
            sliceModel.columns()
        );
    }

    /**
     * A subscripted or sliced reference returns an element rather than a column
     * of the write target, so it is rejected like any other computed returned
     * item, aliased or not.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "UPDATE stages SET handle = :h RETURNING tags[1]|tags[1]",
            "UPDATE stages SET handle = :h RETURNING tags[1:2] AS s|tags[1 : 2]"
        }
    )
    void shouldRejectASubscriptedReturningItem(String sql, String written) {
        Query query = new Query("UpdateStage", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, arraySchema)
        );

        assertEquals(
            "Unsupported RETURNING item %s; return columns of the target table or *"
                .formatted(written),
            exception.getMessage()
        );
    }

    /**
     * A placeholder written beside a subscripted or sliced reference is not
     * typed from the array column, in any clause that types a placeholder from
     * a column, so it is rejected as untyped wherever a cast does not state its
     * type.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM stages WHERE tags[1] = :tag|MANY|:tag|tags[1] = :tag",
            "SELECT id FROM stages WHERE tags[1:2] = :tag|MANY|:tag|tags[1:2] = :tag",
            "SELECT id FROM stages WHERE :tag = tags[1]|MANY|:tag|:tag = tags[1]",
            "SELECT id FROM stages WHERE :tag = tags[1:2]|MANY|:tag|:tag = tags[1:2]",
            "SELECT id FROM stages WHERE tags[1] IN (:a, :b)|MANY|:a|tags[1] IN (:a, :b)",
            "SELECT id FROM stages WHERE tags[1:2] IN (:a)|MANY|:a|tags[1:2] IN (:a)",
            "SELECT id FROM stages WHERE tags[1] LIKE :p|MANY|:p|tags[1] LIKE :p",
            "SELECT id FROM stages WHERE scores[1] BETWEEN :a AND :b"
                + "|MANY|:a|scores[1] BETWEEN :a AND :b",
            "SELECT id FROM stages WHERE scores[1:2] BETWEEN :a AND :b"
                + "|MANY|:a|scores[1:2] BETWEEN :a AND :b",
            "SELECT id FROM stages WHERE tags[1] = ANY(:xs)|MANY|:xs|ANY(:xs)",
            "SELECT s.id FROM stages s JOIN stages t ON s.tags[1] = :x AND t.id = s.id"
                + "|MANY|:x|s.tags[1] = :x",
            "UPDATE stages SET handle = 'x' WHERE tags[1] = :w|EXEC|:w|tags[1] = :w",
            "DELETE FROM stages WHERE tags[1:2] = :w|EXEC|:w|tags[1:2] = :w",
            "INSERT INTO stages (id, handle) VALUES (:id, 'x')"
                + " ON CONFLICT (id) DO UPDATE SET handle = 'y' WHERE stages.tags[1] = :w"
                + "|EXEC|:w|stages.tags[1] = :w",
            "UPDATE stages SET tags[1] = :l WHERE id = :id|EXEC|:l|SET",
            "UPDATE stages SET tags[1:2] = :l WHERE id = :id|EXEC|:l|SET",
            "INSERT INTO stages (id, tags[1]) VALUES (:id, :l)|EXEC|:l|VALUES"
        }
    )
    void shouldRejectAnUncastPlaceholderBesideASubscriptedColumn(
        String sql,
        QueryType type,
        String placeholder,
        String enclosing
    ) {
        Query query = new Query("FindStages", type, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, arraySchema)
        );

        assertEquals(
            "Cannot infer the type of %s in %s; state it with a cast, such as %s::text"
                .formatted(placeholder, enclosing, placeholder),
            exception.getMessage()
        );
    }

    /**
     * A cast placeholder beside a subscripted reference is typed by its cast
     * and named after its own placeholder, so an indexed one is named after its
     * index and a named one keeps its name.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM stages WHERE tags[1] = $1::text|param1|TEXT|false",
            "SELECT id FROM stages WHERE tags[1] = :tag::text|tag|TEXT|false",
            "SELECT id FROM stages WHERE $1::text = tags[1]|param1|TEXT|false",
            "SELECT id FROM stages WHERE tags[1:2] = $1::varchar[]|param1|VARCHAR|true"
        }
    )
    void shouldNameACastPlaceholderBesideASubscriptedColumnAfterItself(
        String sql,
        String name,
        ColumnType type,
        boolean array
    ) {
        QueryModel model = analyzer.analyze(
            new Query("FindStages", QueryType.MANY, sql),
            parser.parse(sql),
            arraySchema
        );

        assertEquals(
            List.of(new QueryParameter(1, name, type, null, array)),
            model.parameters()
        );
    }

    /**
     * A cast placeholder written into a subscripted write target is typed by
     * its cast and named after its own placeholder, beside the parameters the
     * unsubscripted columns of the same statement type.
     */
    @Test
    void shouldNameACastPlaceholderOfASubscriptedWriteTargetAfterItself() {
        String update = "UPDATE stages SET tags[1] = $1::text WHERE id = $2";

        QueryModel updated = analyzer.analyze(
            new Query("UpdateStage", QueryType.EXEC, update),
            parser.parse(update),
            arraySchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "param1", ColumnType.TEXT),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            updated.parameters()
        );

        String insert = "INSERT INTO stages (id, tags[1]) VALUES ($1, $2::text)";

        QueryModel inserted = analyzer.analyze(
            new Query("CreateStage", QueryType.EXEC, insert),
            parser.parse(insert),
            arraySchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "param2", ColumnType.TEXT)
            ),
            inserted.parameters()
        );
    }

    /**
     * A subscripted write target writes into its array column, so that column
     * must exist, as an unsubscripted target must.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "UPDATE stages SET missing[1] = $1::text WHERE id = $2",
            "INSERT INTO stages (id, missing[1]) VALUES ($1, $2::text)"
        }
    )
    void shouldRejectAnUnknownSubscriptedWriteTarget(String sql) {
        Query query = new Query("UpdateStage", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, arraySchema)
        );

        assertEquals(
            "Column not found in table stages: missing",
            exception.getMessage()
        );
    }

    /**
     * A value assigned to a subscripted target that binds no placeholder
     * reaches the database as written, so the statement is analyzed and its
     * executable SQL keeps the subscript.
     */
    @Test
    void shouldAnalyzeALiteralAssignedToASubscriptedColumn() {
        String sql = "UPDATE stages SET tags[1] = 'first' WHERE id = $1";

        QueryModel model = analyzer.analyze(
            new Query("UpdateStage", QueryType.EXEC, sql),
            parser.parse(sql),
            arraySchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(
            "UPDATE stages SET tags[1] = 'first' WHERE id = ?",
            model.executableSql()
        );
    }

    /** A repeated index of one enum type shares one generated parameter. */
    @Test
    void shouldAcceptARepeatedIndexOfOneEnumType() {
        String sql = "SELECT id FROM stages WHERE setting = $1 OR setting = $1";

        QueryModel model = analyzer.analyze(
            new Query("ListStages", QueryType.MANY, sql),
            parser.parse(sql),
            enumSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "setting", ColumnType.ENUM, "stage_setting")),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    /**
     * An {@code INSERT} value that binds no placeholder contributes no
     * parameter and reaches the database as written, while the placeholders
     * beside it keep their own numbering and textual binding order.
     */
    @Test
    void shouldAnalyzeInsertWithNonBindingValues() {
        String sql = "INSERT INTO users (version, name, id) VALUES ($2, now(), $1)";

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "version", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO users (version, name, id) VALUES (?, now(), ?)",
            model.executableSql()
        );
    }

    /** The same insert written with named placeholders. */
    @Test
    void shouldAnalyzeNamedInsertWithNonBindingValues() {
        String sql = "INSERT INTO users (version, name, id) VALUES (:b, now(), :a)";

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "b", ColumnType.INTEGER),
                new QueryParameter(2, "a", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO users (version, name, id) VALUES (?, now(), ?)",
            model.executableSql()
        );
    }

    /**
     * A {@code VALUES} row written inside parentheses states the same single
     * row, so the insert analyzes to the parameters, binding order, and
     * executable SQL of the row it writes.
     */
    @Test
    void shouldAnalyzeInsertWhoseValuesRowIsParenthesized() {
        String sql = "INSERT INTO users (id, name) (VALUES ($1, $2))";

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO users (id, name) (VALUES (?, ?))",
            model.executableSql()
        );
    }

    /**
     * Each {@code VALUES} row pairs with the column list by position, so a
     * named placeholder repeated across two rows is one parameter bound at both
     * of its textual positions.
     */
    @Test
    void shouldAnalyzeInsertWithSeveralValuesRows() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " VALUES (:projectId, :firstUser), (:projectId, :secondUser)";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "projectId", ColumnType.BIGINT),
                new QueryParameter(2, "firstUser", ColumnType.BIGINT),
                new QueryParameter(3, "secondUser", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 1, 3), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO memberships (project_id, user_id)"
                + " VALUES (?, ?), (?, ?)",
            model.executableSql()
        );
    }

    /**
     * Several rows written with indexed placeholders repeat one index across
     * rows and accept a value that binds none, exactly as a single row does.
     */
    @Test
    void shouldAnalyzeIndexedInsertWithSeveralValuesRows() {
        String sql = "INSERT INTO memberships (project_id, user_id, role)"
            + " VALUES ($1, $2, DEFAULT), ($1, $3, 'owner')";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "project_id", ColumnType.BIGINT),
                new QueryParameter(2, "user_id", ColumnType.BIGINT),
                new QueryParameter(3, "user_id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 1, 3), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO memberships (project_id, user_id, role)"
                + " VALUES (?, ?, DEFAULT), (?, ?, 'owner')",
            model.executableSql()
        );
    }

    /**
     * An insert's {@code SELECT} may omit {@code FROM}, which states one row of
     * values: a placeholder item is typed and named by its target column, while
     * a cast placeholder inside a computed item keeps its own name and the type
     * its cast states.
     */
    @Test
    void shouldAnalyzeInsertSelectWithoutFrom() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " SELECT :projectId, unnest(:userIds::bigint[])";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "projectId", ColumnType.BIGINT),
                new QueryParameter(2, "userIds", ColumnType.BIGINT, null, true)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO memberships (project_id, user_id)"
                + " SELECT ?, unnest(?::bigint[])",
            model.executableSql()
        );
    }

    /**
     * A cast placeholder that is a whole item is typed by its cast and named
     * after the column it is written into, as a cast write value is.
     */
    @Test
    void shouldNameAnInsertSelectCastItemAfterItsTargetColumn() {
        String sql = "INSERT INTO memberships (project_id, user_id) SELECT $1::bigint, $2";

        QueryModel model = analyzer.analyze(
            new Query("CreateMembership", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "project_id", ColumnType.BIGINT),
                new QueryParameter(2, "user_id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A subscripted target writes one element of its array column rather than
     * the column, so its item is not typed from that column and a cast
     * placeholder there is named after its own index.
     */
    @Test
    void shouldNotTypeAnInsertSelectItemOfASubscriptedTarget() {
        String sql = "INSERT INTO memberships (project_id, labels[1]) SELECT $1, $2::text";

        QueryModel model = analyzer.analyze(
            new Query("CreateMembership", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "project_id", ColumnType.BIGINT),
                new QueryParameter(2, "param2", ColumnType.TEXT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * An insert's {@code SELECT} over tables resolves its own sources and types
     * its join condition, {@code WHERE}, and pagination placeholders by the
     * clause rules of a read, while its direct column items only resolve.
     */
    @Test
    void shouldAnalyzeInsertSelectFromATable() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " SELECT i.project_id, i.user_id"
            + " FROM invitations i"
            + " JOIN users u ON u.id = i.user_id AND u.name = :name"
            + " WHERE u.active = :active"
            + " LIMIT :pageSize";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "active", ColumnType.BOOLEAN),
                new QueryParameter(3, "pageSize", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * A wildcard item stands for the columns of its source in order, so it
     * counts as one item per column and types nothing.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO memberships (project_id, user_id) SELECT * FROM invitations",
            "INSERT INTO memberships (project_id, user_id) SELECT i.* FROM invitations i"
        }
    )
    void shouldCountOneInsertSelectItemPerWildcardColumn(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertTrue(model.parameters().isEmpty());
        assertEquals(sql, model.executableSql());
    }

    /**
     * A {@code WITH} clause written before the {@code INSERT} is read by the
     * insert's own {@code SELECT}, and its body types its placeholders from its
     * own columns.
     */
    @Test
    void shouldAnalyzeInsertSelectReadingACommonTableExpressionOfTheInsert() {
        String sql = "WITH invited AS (SELECT user_id FROM invitations WHERE user_id = :invitedUser)"
            + " INSERT INTO memberships (project_id, user_id)"
            + " SELECT :projectId, invited.user_id FROM invited";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "invitedUser", ColumnType.BIGINT),
                new QueryParameter(2, "projectId", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A {@code WITH} clause written after the column list belongs to the row
     * source, and is analyzed and read exactly as the insert's own is.
     */
    @Test
    void shouldAnalyzeACommonTableExpressionOfAnInsertSelect() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " WITH invited AS (SELECT user_id FROM invitations WHERE user_id = $1)"
            + " SELECT $2, invited.user_id FROM invited";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "user_id", ColumnType.BIGINT),
                new QueryParameter(2, "project_id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A {@code VALUES} source may declare a {@code WITH} clause of its own,
     * whose body is analyzed for its own placeholders even though no source
     * reads it.
     */
    @Test
    void shouldAnalyzeACommonTableExpressionOfAValuesSource() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " WITH invited AS (SELECT user_id FROM invitations WHERE user_id = :invitedUser)"
            + " VALUES (:projectId, :userId)";

        QueryModel model = analyzer.analyze(
            new Query("CreateMembership", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "invitedUser", ColumnType.BIGINT),
                new QueryParameter(2, "projectId", ColumnType.BIGINT),
                new QueryParameter(3, "userId", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * {@code VALUES} rows written inside parentheses, at any depth, state those
     * same rows, on an {@code :exec} and on a returning insert alike.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "ONE|INSERT INTO memberships (project_id) (VALUES ($1)) RETURNING project_id",
            "EXEC|INSERT INTO memberships (project_id) ((VALUES ($1)))"
        }
    )
    void shouldAnalyzeParenthesizedValuesRowsAtAnyDepth(QueryType type, String sql) {
        QueryModel model = analyzer.analyze(
            new Query("CreateMembership", type, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "project_id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
        assertEquals(sql.replace("$1", "?"), model.executableSql());
    }

    /**
     * A multi-row insert accepts a conflict clause and a bare
     * {@code RETURNING *}, which returns the complete row of the target table.
     */
    @Test
    void shouldAnalyzeSeveralValuesRowsWithConflictAndReturningAllColumns() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " VALUES ($1, $2), ($1, $3)"
            + " ON CONFLICT (project_id, user_id) DO NOTHING"
            + " RETURNING *";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.MANY, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals("memberships", model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("project_id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("role", ColumnType.VARCHAR, true),
                new QueryColumn("labels", ColumnType.VARCHAR, true, null, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "project_id", ColumnType.BIGINT),
                new QueryParameter(2, "user_id", ColumnType.BIGINT),
                new QueryParameter(3, "user_id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 1, 3), model.bindingParameterIndexes());
    }

    /**
     * An insert's {@code SELECT} accepts a {@code DO UPDATE} action whose
     * predicate placeholder is typed by the predicate rules, and a returned
     * column list, which keeps the query's own result.
     */
    @Test
    void shouldAnalyzeInsertSelectWithConflictUpdateAndReturningColumns() {
        String sql = "INSERT INTO memberships (project_id, user_id)"
            + " SELECT i.project_id, i.user_id FROM invitations i"
            + " ON CONFLICT (project_id, user_id) DO UPDATE"
            + " SET role = :role"
            + " WHERE memberships.user_id = :owner"
            + " RETURNING project_id, user_id";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.MANY, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("project_id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "role", ColumnType.VARCHAR),
                new QueryParameter(2, "owner", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * Every placeholder of an insert binds in the textual order the query
     * writes it, across the insert's {@code WITH} body, the items and
     * {@code WHERE} of its {@code SELECT}, and its conflict clause.
     */
    @Test
    void shouldBindInsertSelectParametersInTextualOrder() {
        String sql = "WITH invited AS (SELECT user_id FROM invitations WHERE user_id = $4)"
            + " INSERT INTO memberships (project_id, user_id)"
            + " SELECT $3, invited.user_id FROM invited"
            + " WHERE invited.user_id = $2"
            + " ON CONFLICT (project_id, user_id) DO UPDATE SET role = $1";

        QueryModel model = analyzer.analyze(
            new Query("CreateMemberships", QueryType.EXEC, sql),
            parser.parse(sql),
            insertSourceSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "role", ColumnType.VARCHAR),
                new QueryParameter(2, "user_id", ColumnType.BIGINT),
                new QueryParameter(3, "project_id", ColumnType.BIGINT),
                new QueryParameter(4, "user_id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(4, 3, 2, 1), model.bindingParameterIndexes());
    }

    /**
     * An insert writes its rows from parenthesized {@code VALUES} rows or from
     * a {@code SELECT}, so an element that is not a parenthesized row and a
     * column list with no row source at all state no row of this subset.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO memberships (project_id) VALUES ROW(1)",
            "INSERT INTO memberships (project_id) VALUES (1), 2",
            "INSERT INTO memberships (project_id) DEFAULT VALUES"
        }
    )
    void shouldRejectAnInsertRowSourceOutsideTheSubset(String sql) {
        Query query = new Query("CreateMembership", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, insertSourceSchema)
        );

        assertEquals(
            "INSERT requires parenthesized VALUES rows or a SELECT.",
            exception.getMessage()
        );
    }

    /** Each {@code VALUES} row and an insert's {@code SELECT} must write one value per written column. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO memberships (project_id, user_id) VALUES ($1, $2), ($3)",
            "INSERT INTO memberships (project_id) SELECT * FROM invitations"
        }
    )
    void shouldRejectInsertColumnAndValueCountMismatch(String sql) {
        Query query = new Query("CreateMemberships", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, insertSourceSchema)
        );

        assertEquals("INSERT column and value counts must match.", exception.getMessage());
    }

    /**
     * An insert's row source is classified inside its parentheses as a
     * top-level read is, so a set operation there keeps the set-operation
     * diagnostic.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO memberships (project_id, user_id)"
                + " SELECT project_id, user_id FROM invitations"
                + " UNION SELECT project_id, user_id FROM invitations",
            "INSERT INTO memberships (project_id, user_id)"
                + " (SELECT project_id, user_id FROM invitations"
                + " UNION SELECT project_id, user_id FROM invitations)"
        }
    )
    void shouldRejectASetOperationAsAnInsertRowSource(String sql) {
        Query query = new Query("CreateMemberships", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, insertSourceSchema)
        );

        assertEquals(
            "UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.",
            exception.getMessage()
        );
    }

    /**
     * A direct column item resolves against the sources of the insert's
     * {@code SELECT} alone, which expose neither an unknown column nor the
     * insert's target.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "INSERT INTO memberships (project_id, user_id)"
                + " SELECT project_id, missing FROM invitations"
                + "|Column not found in sources invitations: missing",
            "INSERT INTO memberships (project_id, user_id)"
                + " SELECT memberships.project_id, user_id FROM invitations"
                + "|Unknown source qualifier in query: memberships"
        }
    )
    void shouldRejectAnInsertSelectItemOutsideItsSources(String sql, String message) {
        Query query = new Query("CreateMemberships", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, insertSourceSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A returning insert accepts non-binding values without changing the
     * columns it returns.
     */
    @Test
    void shouldAnalyzeReturningInsertWithNonBindingValues() {
        String sql = """
            INSERT INTO users (id, version, name, updated_at)
            VALUES ($1, DEFAULT, 'anon', now())
            RETURNING id, name""";

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.ONE, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());

        assertEquals(
            """
                INSERT INTO users (id, version, name, updated_at)
                VALUES (?, DEFAULT, 'anon', now())
                RETURNING id, name""",
            model.executableSql()
        );
    }

    /**
     * Non-binding assignments of every listed form are accepted, and the
     * assignment placeholder still binds before the predicate placeholder.
     */
    @Test
    void shouldAnalyzeUpdateWithNonBindingAssignments() {
        String sql = """
            UPDATE users
            SET nickname = $2,
                version = version + 1,
                updated_at = now(),
                name = 'anon',
                active = DEFAULT,
                bio = NULL
            WHERE id = $1""";

        QueryModel model = analyzer.analyze(
            new Query("TouchUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());

        assertEquals(
            """
                UPDATE users
                SET nickname = ?,
                    version = version + 1,
                    updated_at = now(),
                    name = 'anon',
                    active = DEFAULT,
                    bio = NULL
                WHERE id = ?""",
            model.executableSql()
        );
    }

    /** A write whose every value binds nothing has no parameter at all. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id, version, updated_at) VALUES (DEFAULT, 1, now())",
            "UPDATE users SET version = version + 1, updated_at = now()"
        }
    )
    void shouldAnalyzeWriteWithoutAnyPlaceholder(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("TouchUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertTrue(model.parameters().isEmpty());
        assertTrue(model.bindingParameterIndexes().isEmpty());
        assertEquals(sql, model.executableSql());
    }

    /**
     * Nothing binds a non-binding value, so its target column's type need not
     * be mapped.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id, tags) VALUES ($1, DEFAULT)",
            "UPDATE users SET tags = NULL WHERE id = $1"
        }
    )
    void shouldAnalyzeNonBindingValueOnAnUnsupportedTypeColumn(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ClearTags", QueryType.EXEC, sql),
            parser.parse(sql),
            unsupportedTypeSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /** The target column of a non-binding value must exist in the table. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id, missing) VALUES ($1, now())",
            "UPDATE users SET missing = now() WHERE id = $1"
        }
    )
    void shouldRejectAnUnknownNonBindingTargetColumn(String sql) {
        Query query = new Query("TouchUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(
            "Column not found in table users: missing",
            exception.getMessage()
        );
    }

    /**
     * A write value that contains a placeholder without being one leaves that
     * placeholder untyped, which is rejected naming the value expression it
     * stands in.
     */
    @Test
    void shouldRejectAPlaceholderInsideAnInsertValue() {
        String sql = "INSERT INTO users (id, name) VALUES ($1, $2 || 'x')";

        Query query = new Query("CreateUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(
            "Cannot infer the type of $2 in $2 || 'x';"
                + " state it with a cast, such as $2::text",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectAPlaceholderInsideAnUpdateAssignment() {
        String sql = "UPDATE users SET name = :n || name WHERE id = :id";

        Query query = new Query("TouchUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(
            "Cannot infer the type of :n in :n || name;"
                + " state it with a cast, such as :n::text",
            exception.getMessage()
        );
    }

    /** An assignment that sets a row of columns at once stays rejected. */
    @Test
    void shouldRejectExcludedWriteValueForm() {
        String sql = "UPDATE users SET (name, active) = ('a', true)";

        Query query = new Query("TouchUsers", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(
            "UPDATE assignments must set one column at a time.",
            exception.getMessage()
        );
    }

    /**
     * A {@code DO UPDATE} action assigns its columns as an {@code UPDATE} does:
     * {@code EXCLUDED.column} and a computed value bind nothing, while the
     * assigned placeholder binds after the {@code VALUES} placeholders, in the
     * textual order the query writes them.
     */
    @Test
    void shouldAnalyzeUpsertThatUpdatesOnConflict() {
        String sql = """
            INSERT INTO users (id, name)
            VALUES ($1, $2)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name,
                active = $3,
                version = users.version + 1,
                updated_at = now()""";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());

        assertEquals(
            """
                INSERT INTO users (id, name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE
                SET name = EXCLUDED.name,
                    active = ?,
                    version = users.version + 1,
                    updated_at = now()""",
            model.executableSql()
        );
    }

    /** The same upsert returns the row the action wrote. */
    @Test
    void shouldAnalyzeReturningUpsertThatUpdatesOnConflict() {
        String sql = """
            INSERT INTO users (id, name)
            VALUES ($1, $2)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name,
                active = $3,
                version = users.version + 1,
                updated_at = now()
            RETURNING id""";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.ONE, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * A named upsert numbers its parameters by first occurrence, so a name
     * repeated by the action binds the parameter the {@code VALUES} row
     * introduced, and a cast placeholder inside an assigned expression is typed
     * by its cast.
     */
    @Test
    void shouldAnalyzeNamedUpsertThatUpdatesOnConflict() {
        String sql = """
            INSERT INTO users (id, name)
            VALUES (:id, :name)
            ON CONFLICT (id) DO UPDATE
            SET name = :name,
                bio = COALESCE(:bio::text, bio)""";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "bio", ColumnType.TEXT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * A {@code DO NOTHING} action binds nothing, so the insert keeps exactly
     * the parameters of its {@code VALUES} row, and a quoted target column
     * resolves by its unquoted name.
     */
    @Test
    void shouldAnalyzeUpsertThatDoesNothingOnConflict() {
        String sql = "INSERT INTO users (id, name) VALUES ($1, $2) ON CONFLICT (\"id\", name) DO NOTHING";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT (\"id\", name) DO NOTHING",
            model.executableSql()
        );
    }

    /** A returning upsert that does nothing still returns one complete row. */
    @Test
    void shouldResolveRowTableForUpsertThatDoesNothingOnConflict() {
        String sql = "INSERT INTO users (id, name) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING RETURNING *";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.OPTIONAL, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals("users", model.rowTable());

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR)
            ),
            model.parameters()
        );
    }

    /**
     * A {@code DO NOTHING} action decides the conflict by itself, so an insert
     * may omit the conflict target and still bind exactly the parameters of its
     * {@code VALUES} row, on an {@code :exec} and on a returning insert alike.
     */
    @Test
    void shouldAnalyzeUpsertWithoutAConflictTarget() {
        List<QueryParameter> parameters = List.of(
            new QueryParameter(1, "id", ColumnType.BIGINT),
            new QueryParameter(2, "name", ColumnType.VARCHAR)
        );

        QueryModel model = analyzeConflict(QueryType.EXEC, "ON CONFLICT DO NOTHING");

        assertEquals(parameters, model.parameters());
        assertEquals(List.of(1, 2), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING",
            model.executableSql()
        );

        QueryModel returning = analyzeConflict(
            QueryType.OPTIONAL,
            "ON CONFLICT DO NOTHING RETURNING id"
        );

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            returning.columns()
        );

        assertEquals(parameters, returning.parameters());
        assertEquals(List.of(1, 2), returning.bindingParameterIndexes());
    }

    /**
     * A named constraint names the conflict PostgreSQL resolves, as the unique
     * index a column-name target matches is also resolved there, so sqlcj
     * accepts it with either action and analyzes only what the action writes.
     */
    @Test
    void shouldAnalyzeUpsertOnANamedConstraint() {
        List<QueryParameter> parameters = List.of(
            new QueryParameter(1, "id", ColumnType.BIGINT),
            new QueryParameter(2, "name", ColumnType.VARCHAR)
        );

        for (QueryType type : List.of(QueryType.EXEC, QueryType.OPTIONAL)) {
            String returning = type == QueryType.EXEC ? "" : " RETURNING id";

            QueryModel doNothing = analyzeConflict(
                type,
                "ON CONFLICT ON CONSTRAINT users_pkey DO NOTHING" + returning
            );

            assertEquals(parameters, doNothing.parameters());
            assertEquals(List.of(1, 2), doNothing.bindingParameterIndexes());

            QueryModel doUpdate = analyzeConflict(
                type,
                "ON CONFLICT ON CONSTRAINT users_pkey DO UPDATE"
                    + " SET name = EXCLUDED.name, active = $3" + returning
            );

            assertEquals(
                List.of(
                    new QueryParameter(1, "id", ColumnType.BIGINT),
                    new QueryParameter(2, "name", ColumnType.VARCHAR),
                    new QueryParameter(3, "active", ColumnType.BOOLEAN)
                ),
                doUpdate.parameters()
            );

            assertEquals(List.of(1, 2, 3), doUpdate.bindingParameterIndexes());
        }
    }

    /**
     * A conflict-target predicate selects the partial unique index PostgreSQL
     * matches, and reads the inserted table alone, so a placeholder compared
     * with one of its columns is typed by the {@code WHERE} rules. A predicate
     * that compares nothing with a placeholder binds nothing of its own.
     */
    @Test
    void shouldAnalyzeAPlaceholderInAConflictTargetPredicate() {
        QueryModel model = analyzeConflict(
            QueryType.EXEC,
            "ON CONFLICT (name) WHERE active = $3 DO NOTHING"
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());

        assertEquals(
            "INSERT INTO users (id, name) VALUES (?, ?)"
                + " ON CONFLICT (name) WHERE active = ? DO NOTHING",
            model.executableSql()
        );

        QueryModel bare = analyzeConflict(
            QueryType.EXEC,
            "ON CONFLICT (name) WHERE active DO NOTHING"
        );

        assertEquals(List.of(1, 2), bare.bindingParameterIndexes());
    }

    /** A named target-predicate placeholder keeps the name it is written with. */
    @Test
    void shouldAnalyzeANamedPlaceholderInAConflictTargetPredicate() {
        String sql = "INSERT INTO users (id, name) VALUES (:id, :name)"
            + " ON CONFLICT (name) WHERE active = :flag DO NOTHING";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "flag", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * A {@code DO UPDATE ... WHERE} predicate reads both the stored row and the
     * proposed {@code EXCLUDED} row, so a qualified column of either one types
     * the placeholder it is compared with.
     */
    @Test
    void shouldAnalyzePlaceholdersInAConflictUpdatePredicate() {
        QueryModel model = analyzeConflict(
            QueryType.EXEC,
            "ON CONFLICT (id) DO UPDATE SET name = $3"
                + " WHERE users.version < $4 AND EXCLUDED.nickname <> $5"
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "name", ColumnType.VARCHAR),
                new QueryParameter(4, "version", ColumnType.INTEGER),
                new QueryParameter(5, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3, 4, 5), model.bindingParameterIndexes());
    }

    /**
     * The inserted table exposes its alias to a conflict predicate, as a read
     * source exposes its own.
     */
    @Test
    void shouldAnalyzeAConflictUpdatePredicateThroughTheInsertedTableAlias() {
        String sql = """
            INSERT INTO users AS u (id, name)
            VALUES ($1, $2)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name
            WHERE u.version < $3""";

        QueryModel model = analyzer.analyze(
            new Query("UpsertUser", QueryType.EXEC, sql),
            parser.parse(sql),
            writeSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "version", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * Both conflict predicates bind in the textual order the query writes them,
     * so a target predicate binds before the {@code DO UPDATE} assignments it
     * is written before.
     */
    @Test
    void shouldBindConflictPredicatesInTextualOrder() {
        QueryModel model = analyzeConflict(
            QueryType.EXEC,
            "ON CONFLICT (name) WHERE active = $5 DO UPDATE"
                + " SET bio = $3 WHERE users.version < $4"
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "bio", ColumnType.TEXT),
                new QueryParameter(4, "version", ColumnType.INTEGER),
                new QueryParameter(5, "active", ColumnType.BOOLEAN)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 5, 3, 4), model.bindingParameterIndexes());
    }

    /**
     * Each conflict predicate resolves a placeholder's column against its own
     * scope: the inserted table and {@code EXCLUDED} both carry the columns a
     * {@code DO UPDATE ... WHERE} predicate reads, so an unqualified one is
     * ambiguous, while a target predicate reads the inserted table alone.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name WHERE version = $3|"
                + "Ambiguous column reference 'version' in sources: users, EXCLUDED",
            "ON CONFLICT (name) WHERE EXCLUDED.active = $3 DO NOTHING|"
                + "Unknown source qualifier in query: EXCLUDED"
        }
    )
    void shouldRejectAConflictPredicateColumnOutsideItsScope(
        String conflictClause,
        String message
    ) {
        String insert = "INSERT INTO users (id, name) VALUES ($1, $2) " + conflictClause;

        assertEquals(
            message,
            conflictRejection(IllegalArgumentException.class, QueryType.EXEC, insert)
                .getMessage()
        );

        assertEquals(
            message,
            conflictRejection(
                IllegalArgumentException.class,
                QueryType.ONE,
                insert + " RETURNING id"
            ).getMessage()
        );
    }

    /**
     * A conflict target element that is an expression or carries a collation or
     * an operator class decides the conflict by something the analyzed model
     * does not describe.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ON CONFLICT (lower(name)) DO NOTHING",
            "ON CONFLICT (name COLLATE \"C\") DO NOTHING",
            "ON CONFLICT (name text_pattern_ops) DO NOTHING"
        }
    )
    void shouldRejectAConflictTargetThatIsNotColumnNames(String conflictClause) {
        assertConflictRejection(
            conflictClause,
            "ON CONFLICT requires a target of column names, such as ON CONFLICT (id)."
        );
    }

    /** The remaining conflict forms outside the subset and their messages. */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "ON CONFLICT DO UPDATE SET name = EXCLUDED.name|"
                + "ON CONFLICT DO UPDATE requires a conflict target, such as"
                + " ON CONFLICT (id) or ON CONFLICT ON CONSTRAINT name.",
            "ON CONFLICT (id) DO UPDATE SET (name, active) = ('a', TRUE)|"
                + "UPDATE assignments must set one column at a time."
        }
    )
    void shouldRejectExcludedConflictForm(String conflictClause, String message) {
        assertConflictRejection(conflictClause, message);
    }

    /**
     * Asserts that one conflict clause is rejected with the same message on an
     * {@code :exec} insert and on a returning insert.
     */
    private void assertConflictRejection(String conflictClause, String message) {
        String insert = "INSERT INTO users (id, name) VALUES ($1, $2) " + conflictClause;

        assertEquals(message, conflictRejection(QueryType.EXEC, insert).getMessage());

        assertEquals(
            message,
            conflictRejection(QueryType.ONE, insert + " RETURNING id").getMessage()
        );
    }

    /** Analyzes one conflict clause on an insert of the write schema. */
    private QueryModel analyzeConflict(QueryType type, String conflictClause) {
        String sql = "INSERT INTO users (id, name) VALUES ($1, $2) " + conflictClause;

        return analyzer.analyze(
            new Query("UpsertUser", type, sql),
            parser.parse(sql),
            writeSchema
        );
    }

    private UnsupportedOperationException conflictRejection(QueryType type, String sql) {
        return conflictRejection(UnsupportedOperationException.class, type, sql);
    }

    private <T extends Throwable> T conflictRejection(
        Class<T> exceptionType,
        QueryType type,
        String sql
    ) {
        Query query = new Query("UpsertUser", type, sql);
        ParsedSql parsedSql = parser.parse(sql);

        return assertThrows(
            exceptionType,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );
    }

    /**
     * A placeholder inside a {@code DO UPDATE} value is analyzed only through
     * its own cast, so an uncast one is rejected as untyped.
     */
    @Test
    void shouldRejectAnUncastPlaceholderInsideAConflictUpdateValue() {
        String sql = """
            INSERT INTO users (id, name)
            VALUES ($1, $2)
            ON CONFLICT (id) DO UPDATE SET version = version + $3""";

        Query query = new Query("UpsertUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(
            "Cannot infer the type of $3 in version + $3;"
                + " state it with a cast, such as $3::text",
            exception.getMessage()
        );
    }

    /**
     * A target column, an assigned column, and a column read from
     * {@code EXCLUDED} must each exist in the inserted table.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users (id) VALUES ($1) ON CONFLICT (missing) DO NOTHING",
            "INSERT INTO users (id) VALUES ($1) ON CONFLICT (id) DO UPDATE SET missing = 'a'",
            "INSERT INTO users (id) VALUES ($1) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.missing"
        }
    )
    void shouldRejectAnUnknownConflictColumn(String sql) {
        Query query = new Query("UpsertUser", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(
            "Column not found in table users: missing",
            exception.getMessage()
        );
    }

    /**
     * A placeholder that is the argument of {@code = ANY} is one list of the
     * compared column's type, named after that column and bound at its own
     * {@code ?} position, however the operator and the column are spelled. The
     * rest of the statement, including the operator, reaches JDBC as written.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users WHERE id = ANY($1)",
            "SELECT id FROM users WHERE id = any($1)",
            "SELECT u.id FROM users u WHERE u.id = ANY($1)"
        }
    )
    void shouldResolveListParameterOfAnyFromItsComparedColumn(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            anyListSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT, null, true)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
        assertEquals(sql.replace("$1", "?"), model.executableSql());
    }

    /**
     * A named list placeholder keeps its own name, and one name used by two
     * list predicates is one parameter bound at both of its positions.
     */
    @Test
    void shouldResolveNamedListParameterOfAnyAtEveryOccurrence() {
        String sql = "SELECT id FROM users WHERE id = ANY(:ids) OR parent_id = ANY(:ids)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            anyListSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "ids", ColumnType.BIGINT, null, true)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    /**
     * A list parameter carries its column's enum type and blank-padded
     * spelling, so that generation binds the labels of an enum list and the
     * {@code bpchar} elements of a blank-padded character list.
     */
    @Test
    void shouldCarryTheEnumTypeAndBlankPaddingOfAListParameterOfAny() {
        String sql = "SELECT id FROM users WHERE role = ANY($1) OR code = ANY($2)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            anyListSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "role", ColumnType.ENUM, "user_role", true),
                new QueryParameter(2, "code", ColumnType.VARCHAR, null, true, true)
            ),
            model.parameters()
        );
    }

    /**
     * The {@code WHERE} clause of a write analyzes a list predicate as a read
     * does, so the written value is bound before the list it selects rows by.
     */
    @Test
    void shouldResolveListParameterOfAnyInWrites() {
        String delete = "DELETE FROM users WHERE id = ANY($1)";

        QueryModel deleted = analyzer.analyze(
            new Query("DeleteUsers", QueryType.EXEC, delete),
            parser.parse(delete),
            anyListSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT, null, true)),
            deleted.parameters()
        );

        assertEquals(List.of(1), deleted.bindingParameterIndexes());

        String update = "UPDATE users SET name = $1 WHERE id = ANY($2)";

        QueryModel updated = analyzer.analyze(
            new Query("RenameUsers", QueryType.EXEC, update),
            parser.parse(update),
            anyListSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "id", ColumnType.BIGINT, null, true)
            ),
            updated.parameters()
        );

        assertEquals(List.of(1, 2), updated.bindingParameterIndexes());
    }

    /**
     * A cast that is the direct argument of {@code = ANY} states its own type
     * and keeps the compared column's name, as a cast beside a compared column
     * does, while a named one keeps its own name.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users WHERE id = ANY($1::bigint[])|id",
            "SELECT id FROM users WHERE id = ANY(CAST($1 AS bigint[]))|id",
            "SELECT id FROM users WHERE id = ANY(:ids::bigint[])|ids"
        }
    )
    void shouldNameTheCastArgumentOfAnyAfterItsComparedColumn(String sql, String name) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            anyListSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, name, ColumnType.BIGINT, null, true)),
            model.parameters()
        );
    }

    /**
     * A cast placeholder under an operator that is not the list predicate is
     * named after its own placeholder, as any other unnamed cast is.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users WHERE id <> ANY($1::bigint[])|BIGINT",
            "SELECT id FROM users WHERE tags && $1::varchar[]|VARCHAR"
        }
    )
    void shouldNameACastArgumentOutsideTheListPredicateAfterItsPlaceholder(
        String sql,
        ColumnType type
    ) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            anyListSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "param1", type, null, true)),
            model.parameters()
        );
    }

    /**
     * A list parameter takes the compared column's type as its element type, so
     * a column sqlcj binds no array of is rejected: an array column, whose own
     * values are not elements of its type, and a {@code BYTEA}, {@code JSON},
     * or {@code JSONB} column, which has no array element binding.
     */
    @ParameterizedTest
    @CsvSource(
        {
            "tags, VARCHAR[]",
            "avatar, BYTEA",
            "payload, JSON",
            "document, JSONB"
        }
    )
    void shouldRejectAListParameterOfAColumnWithoutAnArrayBinding(
        String column,
        String type
    ) {
        String sql = "SELECT id FROM users WHERE %s = ANY($1)".formatted(column);

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, anyListSchema)
        );

        assertEquals(
            ("An = ANY placeholder requires a non-array column of a type other than BYTEA, "
                + "JSON, and JSONB, but %s is %s.").formatted(column, type),
            exception.getMessage()
        );
    }

    /**
     * Only the exact list-predicate shape binds a list, so another operator,
     * another operand order, another quantifier, a modifier, and an argument
     * that is not one placeholder leave their placeholders untyped, each
     * rejected naming the expression it stands in.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id FROM users WHERE id <> ANY($1)|ANY($1)",
            "SELECT id FROM users WHERE id = SOME($1)|SOME($1)",
            "SELECT id FROM users WHERE ANY($1) = id|ANY($1)",
            "SELECT id FROM users WHERE id = ANY(ARRAY[$1, $2])|ARRAY[$1, $2]",
            "SELECT id FROM users WHERE id = ANY(DISTINCT $1)|ANY(DISTINCT $1)",
            "SELECT id FROM users WHERE id = ANY($1 ORDER BY id)|ANY($1 ORDER BY id)"
        }
    )
    void shouldRejectAPlaceholderOutsideTheListPredicateShape(String sql, String enclosing) {
        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, anyListSchema)
        );

        assertEquals(
            "Cannot infer the type of $1 in %s; state it with a cast, such as $1::text"
                .formatted(enclosing),
            exception.getMessage()
        );
    }

    /**
     * A list and a value of its element type are two Java types, so one name
     * used as both has no single type and is rejected.
     */
    @Test
    void shouldRejectANameUsedAsBothAListAndItsElement() {
        String sql = "SELECT id FROM users WHERE id = ANY(:ids) OR id = :ids";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, anyListSchema)
        );

        assertEquals(
            "Placeholder :ids has conflicting types: List<Long> and Long",
            exception.getMessage()
        );
    }

    /**
     * sqlcj models one namespace, so an unqualified and a
     * {@code public}-qualified query source are the same table and analyze
     * alike. The qualifier is matched without its SQL identifier delimiters and
     * case-insensitively.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "users",
            "public.users",
            "\"public\".\"users\"",
            "PUBLIC.users"
        }
    )
    void shouldResolveAPublicQualifiedSource(String declaredName) {
        String sql = "SELECT id, name FROM %s WHERE id = $1".formatted(declaredName);

        QueryModel model = analyzer.analyze(
            new Query("GetUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );
    }

    /**
     * A source qualified with a schema other than {@code public} names a table
     * sqlcj leaves unmodeled, so it is rejected as a missing table rather than
     * resolving to the modeled table of the same unqualified name, in every
     * query that names a table.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "MANY|SELECT id FROM reporting.users",
            "MANY|SELECT u.id FROM users u JOIN reporting.profiles p ON p.user_id = u.id",
            "MANY|SELECT u.id FROM users u LEFT JOIN reporting.profiles p ON p.user_id = u.id",
            "EXEC|INSERT INTO reporting.users (name) VALUES ($1)",
            "EXEC|UPDATE reporting.users SET name = $1 WHERE id = $2",
            "EXEC|DELETE FROM reporting.users WHERE id = $1"
        }
    )
    void shouldRejectASourceOfAnotherSchema(QueryType type, String sql) {
        Query query = new Query("TouchUsers", type, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Table not found in schema: reporting."
                + (sql.contains("profiles") ? "profiles" : "users"),
            exception.getMessage()
        );
    }

    /**
     * A cast type resolves its enum in the one namespace sqlcj models, so a
     * {@code public}-qualified cast names the declared enum, scalar and array
     * alike, and a cast type of any other schema is rejected with its declared
     * text.
     */
    @Test
    void shouldResolveAPublicQualifiedEnumCastType() {
        String sql = "SELECT handle::public.stage_setting[] AS s FROM stages";

        QueryModel model = analyzer.analyze(
            new Query("ListStageSettings", QueryType.MANY, sql),
            parser.parse(sql),
            enumSchema
        );

        assertEquals(
            List.of(new QueryColumn("s", ColumnType.ENUM, true, "stage_setting", true)),
            model.columns()
        );

        String unmapped = "SELECT handle::reporting.stage_setting AS s FROM stages";
        Query query = new Query("ListStageSettings", QueryType.MANY, unmapped);
        ParsedSql parsedSql = parser.parse(unmapped);

        assertEquals(
            "Result column 's' has unsupported cast type REPORTING.STAGE_SETTING",
            assertThrows(
                UnsupportedOperationException.class,
                () -> analyzer.analyze(query, parsedSql, enumSchema)
            )
                .getMessage()
        );
    }

    /**
     * A source that names a common table expression reads that expression, so
     * its result columns are the body's own columns and an uncast placeholder
     * of the body is typed and named from the column it is compared with.
     */
    @Test
    void shouldResolveCommonTableExpressionSource() {
        String sql = """
            WITH recent AS (SELECT user_id, total
                            FROM orders
                            WHERE total = $1)
            SELECT recent.user_id, recent.total
            FROM recent
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListRecentOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("recent", model.table());
        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A common table expression sees the ones written before it in its own
     * list, so a chain of expressions resolves its columns through each link,
     * and an aliased source exposes the chain's last link under its alias.
     */
    @Test
    void shouldResolveChainedCommonTableExpressions() {
        String sql = """
            WITH matching AS (SELECT id, user_id, total
                              FROM orders
                              WHERE total = $1),
                 totals AS (SELECT user_id, total AS amount
                            FROM matching)
            SELECT t.user_id, t.amount
            FROM totals t
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListOrderTotals", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("t", model.table());

        assertEquals(
            List.of(
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("amount", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A common table expression named like a schema table shadows that table
     * for the sources written after it, while its own body reads the table, as
     * PostgreSQL scopes it.
     */
    @Test
    void shouldReadTheSchemaTableInsideACommonTableExpressionNamedLikeIt() {
        String sql = """
            WITH orders AS (SELECT id, total
                            FROM orders
                            WHERE total = $1)
            SELECT orders.id
            FROM orders
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListOrderIds", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A subquery source analyzes its own {@code WITH} list, so a common table
     * expression declared inside it is a source of that body alone.
     */
    @Test
    void shouldResolveCommonTableExpressionDeclaredInsideASubquerySource() {
        String sql = """
            SELECT s.total
            FROM (WITH matching AS (SELECT total
                                    FROM orders
                                    WHERE total = $1)
                  SELECT total
                  FROM matching) s
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListTotals", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("s", model.table());

        assertEquals(
            List.of(new QueryColumn("total", ColumnType.DECIMAL, true)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A subquery may be joined like a table, and its columns are read through
     * its alias.
     */
    @Test
    void shouldResolveSubquerySourceOfAJoin() {
        String sql = """
            SELECT u.name, s.total
            FROM users u
            JOIN (SELECT user_id, total
                  FROM orders
                  WHERE total = $1) s ON s.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A left join of a derived source reads every one of its columns as
     * nullable, including the ones its body declares {@code NOT NULL}, both
     * selected by name and expanded by a wildcard.
     */
    @Test
    void shouldReadColumnsOfALeftJoinedDerivedSourceAsNullable() {
        String sql = """
            WITH totals AS (SELECT user_id, total
                            FROM orders)
            SELECT u.id, totals.user_id, totals.*
            FROM users u
            LEFT JOIN totals ON totals.user_id = u.id
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserTotals", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, true),
                new QueryColumn("user_id", ColumnType.BIGINT, true),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    /**
     * A derived source exposes an aliased {@code COUNT(*)} and an aliased cast
     * as columns of its own, so a reader of the source takes their types
     * without restating the cast.
     */
    @Test
    void shouldResolveCountAndCastColumnsThroughADerivedSource() {
        String sql = """
            WITH stats AS (SELECT user_id,
                                  COUNT(*) AS order_count,
                                  SUM(total)::numeric AS amount
                           FROM orders
                           GROUP BY user_id)
            SELECT stats.user_id, stats.order_count, stats.amount
            FROM stats
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListOrderStats", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("order_count", ColumnType.BIGINT, false),
                new QueryColumn("amount", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    /**
     * A derived source returns no shared table row, so {@code SELECT *} over a
     * common table expression or a subquery returns a result specific to its
     * own query, even when the source is named like a schema table.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "WITH users AS (SELECT id, name FROM users) SELECT * FROM users|users",
            "SELECT * FROM (SELECT id, name FROM users) s|s"
        }
    )
    void shouldResolveQuerySpecificResultForAllColumnsOfADerivedSource(String sql, String table) {
        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(table, model.table());
        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * Every placeholder binds in the textual order the parser reported,
     * whatever order the analyzer resolves the scopes in, so a common table
     * expression body, a subquery source, and the outer query bind in the order
     * the query writes them.
     */
    @Test
    void shouldBindDerivedSourceParametersInTextualOrder() {
        String sql = """
            WITH recent AS (SELECT user_id
                            FROM orders
                            WHERE total = $3)
            SELECT s.user_id
            FROM (SELECT user_id
                  FROM recent
                  WHERE user_id = $2) s
            WHERE s.user_id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListRecentUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "user_id", ColumnType.BIGINT),
                new QueryParameter(2, "user_id", ColumnType.BIGINT),
                new QueryParameter(3, "total", ColumnType.DECIMAL)
            ),
            model.parameters()
        );

        assertEquals(List.of(3, 2, 1), model.bindingParameterIndexes());
    }

    /**
     * A returning write may declare a common table expression, whose body is
     * analyzed by its own clause rules while the returned columns stay the
     * write target's.
     */
    @Test
    void shouldAnalyzeReturningWriteWithACommonTableExpression() {
        String sql = """
            WITH known AS (SELECT id
                           FROM users
                           WHERE name = $1)
            INSERT INTO users (id)
            VALUES ($2)
            RETURNING id
            """;

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.ONE, sql),
            parser.parse(sql),
            schema
        );

        assertEquals("users", model.table());
        assertNull(model.rowTable());

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * The common table expressions of a write without {@code RETURNING} are
     * analyzed as well, so an uncast placeholder of a body is typed from the
     * column it is compared with.
     */
    @Test
    void shouldAnalyzeCommonTableExpressionOfAWriteWithoutReturning() {
        String sql = """
            WITH recent AS (SELECT user_id
                            FROM orders
                            WHERE total = $1)
            DELETE FROM users
            WHERE id = $2
            """;

        QueryModel model = analyzer.analyze(
            new Query("DeleteUser", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "total", ColumnType.DECIMAL),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A common table expression no source of the query itself reads is
     * analyzed too, so its body's placeholders are typed, and a predicate
     * subquery reads it as the derived source it declares.
     */
    @Test
    void shouldAnalyzeACommonTableExpressionReadOnlyInsideAPredicateSubquery() {
        String sql = """
            WITH recent AS (SELECT user_id
                            FROM orders
                            WHERE total = $1)
            SELECT id
            FROM users
            WHERE id IN (SELECT user_id FROM recent)
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListRecentUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A {@code MATERIALIZED} and a {@code NOT MATERIALIZED} clause state how
     * PostgreSQL evaluates the body, not what it returns, so both analyze like
     * a plain one.
     */
    @ParameterizedTest
    @ValueSource(strings = { "MATERIALIZED ", "NOT MATERIALIZED ", "" })
    void shouldAcceptMaterializedCommonTableExpression(String materialized) {
        String sql = "WITH recent AS %s(SELECT id FROM orders WHERE total = $1) SELECT id FROM recent"
            .formatted(materialized);

        QueryModel model = analyzer.analyze(
            new Query("ListRecentOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A subquery source that is not {@code LATERAL} sees no source of the query
     * around it, as PostgreSQL scopes it, so a reference to one is an unknown
     * qualifier.
     */
    @Test
    void shouldRejectAnOuterSourceReferenceInsideASubquerySource() {
        String sql = "SELECT s.name FROM users u JOIN (SELECT u.name FROM orders) s ON TRUE";

        Query query = new Query("ListUserOrders", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals("Unknown source qualifier in query: u", exception.getMessage());
    }

    /**
     * The derived-source forms outside this subset are rejected with their own
     * diagnostic: a recursive and a data-modifying {@code WITH} item, a
     * subquery source without an alias, a column list on a {@code WITH} name or
     * on a subquery alias, a body that is not a plain {@code SELECT}, which is
     * classified inside its own parentheses, a {@code LATERAL} source, which is
     * classified first, a doubly parenthesized source, which states a source
     * kind of its own, and a derived source whose columns repeat a name.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "MANY|WITH RECURSIVE recent AS (SELECT id FROM users) SELECT id FROM recent"
                + "|WITH RECURSIVE is not supported.",
            "MANY|WITH moved AS (UPDATE users SET name = $1 WHERE id = $2 RETURNING id)"
                + " SELECT id FROM moved"
                + "|A data-modifying statement in WITH is not supported;"
                + " run it as a query of its own.",
            "EXEC|WITH removed AS (DELETE FROM users WHERE id = $1 RETURNING id)"
                + " DELETE FROM users WHERE id = $2"
                + "|A data-modifying statement in WITH is not supported;"
                + " run it as a query of its own.",
            "EXEC|WITH added AS (INSERT INTO users (id) VALUES ($1) RETURNING id)"
                + " UPDATE users SET name = $2 WHERE id = $3"
                + "|A data-modifying statement in WITH is not supported;"
                + " run it as a query of its own.",
            "MANY|WITH rows AS (VALUES (1)) SELECT * FROM rows"
                + "|Only SELECT, INSERT, UPDATE, and DELETE statements are supported.",
            "MANY|WITH combined AS (SELECT id FROM users UNION SELECT id FROM profiles)"
                + " SELECT id FROM combined"
                + "|UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.",
            "MANY|SELECT s.id FROM (SELECT id FROM users UNION SELECT id FROM profiles) s"
                + "|UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.",
            "MANY|WITH recent (n) AS (SELECT id FROM users) SELECT n FROM recent"
                + "|A column list on recent is not supported;"
                + " alias the columns in its SELECT instead.",
            "MANY|SELECT s.n FROM (SELECT id FROM users) AS s(n)"
                + "|A column list on s is not supported; alias the columns in its SELECT instead.",
            "MANY|SELECT id FROM (SELECT id FROM users)"
                + "|A subquery in FROM requires an alias, such as (SELECT ...) AS recent.",
            "MANY|SELECT u.id FROM users u JOIN (SELECT user_id FROM orders) ON TRUE"
                + "|A subquery in FROM requires an alias, such as (SELECT ...) AS recent.",
            "MANY|SELECT s.id FROM LATERAL (SELECT id FROM users) s"
                + "|LATERAL is not supported.",
            "MANY|SELECT s.id FROM ((SELECT id FROM users)) s"
                + "|Only table sources are supported.",
            "MANY|WITH pairs AS (SELECT u.id, p.id FROM users u JOIN profiles p"
                + " ON p.user_id = u.id) SELECT * FROM pairs"
                + "|Derived source pairs has more than one column named id;"
                + " give each a distinct alias.",
            "MANY|SELECT s.id FROM (SELECT id, name AS ID FROM users) s"
                + "|Derived source s has more than one column named ID;"
                + " give each a distinct alias."
        }
    )
    void shouldRejectExcludedDerivedSourceForm(QueryType type, String sql, String message) {
        Query query = new Query("ReadUsers", type, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A predicate subquery is analyzed by the clause rules of a read, so an
     * uncast placeholder of its {@code WHERE} is typed and named from the
     * column of its own source, in each subquery form the predicate rules
     * reach: {@code EXISTS} and its negation, {@code IN} and {@code NOT IN}
     * over a query, whatever the tested value is, and a comparison subquery as
     * either operand of any comparison operator. The subquery itself reaches
     * the database as written.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "EXISTS (SELECT 1 FROM orders WHERE total = :total)",
            "NOT EXISTS (SELECT 1 FROM orders WHERE total = :total)",
            "id IN (SELECT user_id FROM orders WHERE total = :total)",
            "id NOT IN (SELECT user_id FROM orders WHERE total = :total)",
            "lower(name) IN (SELECT user_id FROM orders WHERE total = :total)",
            "id = (SELECT user_id FROM orders WHERE total = :total)",
            "(SELECT user_id FROM orders WHERE total = :total) = id",
            "id > (SELECT user_id FROM orders WHERE total = :total)",
            "NOT (id IN (SELECT user_id FROM orders WHERE total = :total))"
        }
    )
    void shouldTypePredicateSubqueryPlaceholderFromItsOwnColumn(String predicate) {
        String sql = "SELECT id FROM users WHERE " + predicate;

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
        assertEquals(sql.replace(":total", "?"), model.executableSql());
    }

    /**
     * A predicate subquery of a join condition is analyzed as one of a
     * {@code WHERE} is, over the sources written up to that join.
     */
    @Test
    void shouldTypePredicateSubqueryPlaceholderOfAJoinCondition() {
        String sql = """
            SELECT u.id
            FROM users u
                     JOIN orders o ON o.user_id = u.id
                AND EXISTS (SELECT 1
                            FROM profiles p
                            WHERE p.user_id = u.id
                              AND p.nickname = :nickname)
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUserOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * The {@code WHERE} of an {@code UPDATE} and of a {@code DELETE} analyzes
     * its predicate subqueries as a read's does, and the subquery reads the
     * write target as well as its own source.
     */
    @Test
    void shouldTypePredicateSubqueryPlaceholderOfAnUpdate() {
        String sql = """
            UPDATE users
            SET name = $1
            WHERE EXISTS (SELECT 1
                          FROM orders
                          WHERE orders.user_id = users.id
                            AND orders.total = $2)
            """;

        QueryModel model = analyzer.analyze(
            new Query("ClearUserNames", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "total", ColumnType.DECIMAL)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    @Test
    void shouldTypePredicateSubqueryPlaceholderOfADelete() {
        String sql = "DELETE FROM users WHERE id IN (SELECT user_id FROM orders WHERE total = $1)";

        QueryModel model = analyzer.analyze(
            new Query("DeleteOrderingUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
        assertEquals(sql.replace("$1", "?"), model.executableSql());
    }

    /**
     * A {@code DO UPDATE} predicate analyzes its subqueries over the inserted
     * table and {@code EXCLUDED}, so a subquery placeholder there is typed from
     * the subquery's own column and binds after the conflict assignments.
     */
    @Test
    void shouldTypePredicateSubqueryPlaceholderOfAConflictPredicate() {
        String sql = """
            INSERT INTO users (id, name)
            VALUES ($1, $2)
            ON CONFLICT (id) DO UPDATE SET name = $3
            WHERE EXISTS (SELECT 1
                          FROM orders
                          WHERE orders.user_id = users.id
                            AND orders.total = $4)
            """;

        QueryModel model = analyzer.analyze(
            new Query("CreateUser", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "name", ColumnType.VARCHAR),
                new QueryParameter(4, "total", ColumnType.DECIMAL)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3, 4), model.bindingParameterIndexes());
    }

    /**
     * A subquery sees the sources of the query around it, so a placeholder
     * compared with a column of an enclosing level is typed from that column,
     * whether the query qualifies the column or not.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT u.id FROM users u"
                + " WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND u.name = :name)",
            "SELECT id FROM users"
                + " WHERE EXISTS (SELECT 1 FROM orders"
                + " WHERE orders.user_id = users.id AND name = :name)"
        }
    )
    void shouldTypeCorrelatedPlaceholderFromAnEnclosingSource(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListOrderingUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * An unqualified column resolves in the innermost level that has it, so a
     * column name an enclosing source also exposes is read from the subquery's
     * own source.
     */
    @Test
    void shouldResolveAnUnqualifiedColumnInTheInnermostLevelThatHasIt() {
        String sql = """
            WITH shadow AS (SELECT total AS id
                            FROM orders)
            SELECT u.id
            FROM users u
            WHERE EXISTS (SELECT 1 FROM shadow WHERE id = :id)
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A qualified column resolves through the innermost source of that exposed
     * name, so a subquery source that reuses the alias of an enclosing source
     * is the one its own references read.
     */
    @Test
    void shouldResolveAReusedAliasInTheInnermostLevel() {
        String sql = "SELECT u.id FROM users u"
            + " WHERE EXISTS (SELECT 1 FROM orders u WHERE u.total = :total)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * One column name exposed by a source of two levels is ambiguous in
     * neither, because the reference resolves in the innermost level alone, as
     * PostgreSQL resolves it.
     */
    @Test
    void shouldAcceptOneColumnNameExposedByTwoLevels() {
        String sql = "SELECT o.id FROM orders o"
            + " WHERE EXISTS (SELECT 1 FROM profiles WHERE user_id = :userId)";

        QueryModel model = analyzer.analyze(
            new Query("ListOrders", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "userId", ColumnType.BIGINT)),
            model.parameters()
        );
    }

    /**
     * A column no level in scope exposes names every visible source, from the
     * innermost level outward.
     */
    @Test
    void shouldRejectAnUnknownColumnNamingEveryVisibleSource() {
        String sql = """
            SELECT u.id
            FROM users u
            WHERE EXISTS (SELECT 1
                          FROM orders o
                          WHERE EXISTS (SELECT 1
                                        FROM profiles p
                                        WHERE missing = :missing))
            """;

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Column not found in sources p, o, u: missing",
            exception.getMessage()
        );
    }

    /**
     * A column two sources of one level expose is ambiguous in that level,
     * whatever the levels around it expose.
     */
    @Test
    void shouldRejectAnAmbiguousColumnWithinOnePredicateSubqueryLevel() {
        String sql = "SELECT id FROM users"
            + " WHERE EXISTS (SELECT 1 FROM orders o JOIN profiles p ON p.user_id = o.user_id"
            + " WHERE user_id = :userId)";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Ambiguous column reference 'user_id' in sources: o, p",
            exception.getMessage()
        );
    }

    /**
     * A subquery inside a subquery is one more level, so its placeholders are
     * typed from its own source and from each enclosing one, in textual order.
     */
    @Test
    void shouldTypeNestedPredicateSubqueryPlaceholdersFromEveryLevel() {
        String sql = """
            SELECT u.id
            FROM users u
            WHERE EXISTS (SELECT 1
                          FROM orders o
                          WHERE EXISTS (SELECT 1
                                        FROM profiles p
                                        WHERE p.nickname = :nickname
                                          AND u.name = :name
                                          AND o.total = :total))
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "nickname", ColumnType.VARCHAR),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "total", ColumnType.DECIMAL)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * One named placeholder typed in two levels is one parameter, bound at each
     * of its textual positions.
     */
    @Test
    void shouldShareOneParameterBetweenTwoLevelsOfOneName() {
        String sql = "SELECT id FROM users"
            + " WHERE id = :id AND EXISTS (SELECT 1 FROM orders WHERE user_id = :id)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    /**
     * Placeholders bind in the textual order of the query, so a placeholder of
     * a subquery binds before one written after it in the enclosing predicate,
     * whatever their indexes are.
     */
    @Test
    void shouldBindPredicateSubqueryParametersInTextualOrder() {
        String sql = "SELECT id FROM users"
            + " WHERE EXISTS (SELECT 1 FROM orders WHERE total = $2) AND name = $1";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "total", ColumnType.DECIMAL)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 1), model.bindingParameterIndexes());
    }

    /**
     * A negated predicate states the operands of the predicate it negates, so
     * the predicate rules reach through {@code NOT} and type its placeholder.
     */
    @Test
    void shouldTypePlaceholderOfANegatedPredicate() {
        String sql = "SELECT id FROM users WHERE NOT (id = :id)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "id", ColumnType.BIGINT)),
            model.parameters()
        );

        assertEquals(List.of(1), model.bindingParameterIndexes());
    }

    /**
     * A predicate subquery states no result column of the query, so its
     * {@code SELECT} list accepts a literal, a wildcard, and a column beside
     * them, each reaching the database as written.
     */
    @ParameterizedTest
    @ValueSource(strings = { "1", "*", "orders.*", "1, orders.id, *" })
    void shouldAcceptPredicateSubquerySelectItems(String selectItems) {
        String sql = "SELECT id FROM users WHERE EXISTS (SELECT %s FROM orders WHERE total = :total)"
            .formatted(selectItems);

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "total", ColumnType.DECIMAL)),
            model.parameters()
        );
    }

    /**
     * A column the schema recorded without a mapped type is accepted in a
     * predicate subquery's {@code SELECT} list, because the subquery generates
     * no result column of that type.
     */
    @Test
    void shouldAcceptAnUnmappedColumnOfAPredicateSubquerySelectList() {
        String sql = "SELECT id FROM users"
            + " WHERE EXISTS (SELECT u.tags FROM users u WHERE u.name = :name)";

        QueryModel model = analyzer.analyze(
            new Query("ListUsers", QueryType.MANY, sql),
            parser.parse(sql),
            unsupportedTypeSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "name", ColumnType.VARCHAR)),
            model.parameters()
        );
    }

    /**
     * A direct column of a predicate subquery's {@code SELECT} list still
     * names a column of the sources in scope.
     */
    @Test
    void shouldRejectAnUnknownColumnOfAPredicateSubquerySelectList() {
        String sql = "SELECT id FROM users WHERE EXISTS (SELECT missing FROM orders)";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Column not found in sources orders, users: missing",
            exception.getMessage()
        );
    }

    /**
     * A predicate subquery is classified inside its own parentheses as a
     * top-level read is, so a set operation there keeps the set-operation
     * diagnostic.
     */
    @Test
    void shouldRejectASetOperationInsideAPredicateSubquery() {
        String sql = "SELECT id FROM users"
            + " WHERE EXISTS (SELECT 1 FROM orders UNION SELECT 1 FROM profiles)";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.",
            exception.getMessage()
        );
    }

    /**
     * The common table expressions a write declares are in scope of its
     * predicate subqueries, so a subquery source may read one and its
     * placeholders are typed from that derived source's columns.
     */
    @Test
    void shouldResolveAWriteCommonTableExpressionInsideItsPredicateSubquery() {
        String sql = """
            WITH recent AS (SELECT user_id
                            FROM orders
                            WHERE total = $1)
            DELETE FROM users
            WHERE id IN (SELECT user_id FROM recent WHERE user_id = $2)
            """;

        QueryModel model = analyzer.analyze(
            new Query("DeleteRecentUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "total", ColumnType.DECIMAL),
                new QueryParameter(2, "user_id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A source an {@code UPDATE ... FROM} clause adds is in scope of the
     * update's {@code WHERE}, so a placeholder compared with one of its columns
     * is typed and named from that column.
     */
    @Test
    void shouldTypeUpdateWhereParameterFromAnAddedSourceColumn() {
        String sql = """
            UPDATE users u
            SET name = :name
            FROM profiles p
            WHERE p.user_id = u.id
              AND p.nickname = :nickname
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameProfiledUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.table());
        assertTrue(model.columns().isEmpty());

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A source a {@code DELETE ... USING} clause adds is in scope of the
     * delete's {@code WHERE} and of its {@code RETURNING} clause, and the
     * executable SQL keeps the clause as the query writes it.
     */
    @Test
    void shouldTypeDeleteWhereParameterFromAUsingSourceColumn() {
        String sql = """
            DELETE FROM users u
            USING profiles p
            WHERE p.user_id = u.id
              AND p.nickname = :nickname
            RETURNING u.id, p.nickname
            """;

        QueryModel model = analyzer.analyze(
            new Query("DeleteProfiledUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.table());
        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(
            """
                DELETE FROM users u
                USING profiles p
                WHERE p.user_id = u.id
                  AND p.nickname = ?
                RETURNING u.id, p.nickname
                """,
            model.executableSql()
        );
    }

    /**
     * A join inside an {@code UPDATE ... FROM} clause types its own
     * {@code ON} placeholders from the columns of the joined sources, and the
     * joined source is in scope of the update's {@code WHERE}.
     */
    @Test
    void shouldTypeJoinConditionParameterOfAnUpdateFromClause() {
        String sql = """
            UPDATE users u
            SET name = $1
            FROM profiles p
            JOIN orders o ON o.user_id = p.user_id
                AND o.total = $2
            WHERE u.id = p.user_id
              AND u.id = $3
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameOrderingUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "total", ColumnType.DECIMAL),
                new QueryParameter(3, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());
    }

    /**
     * An {@code UPDATE ... FROM} clause may write its sources as a
     * comma-separated list, which is PostgreSQL's own form of the clause, and
     * each of them is in scope of the update's {@code WHERE}.
     */
    @Test
    void shouldResolveCommaSeparatedUpdateFromSources() {
        String sql = """
            UPDATE users u
            SET name = :name
            FROM profiles p, orders o
            WHERE p.user_id = u.id
              AND o.user_id = u.id
              AND o.total = :total
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameOrderingUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "total", ColumnType.DECIMAL)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A subquery source of an {@code UPDATE ... FROM} clause is a derived
     * source whose columns are its body's result columns, which the update's
     * {@code WHERE} and {@code RETURNING} read, while the body types its own
     * placeholders from its own columns.
     */
    @Test
    void shouldResolveSubquerySourceOfAnUpdateFromClause() {
        String sql = """
            UPDATE users u
            SET name = recent.nickname
            FROM (SELECT p.user_id, p.nickname
                  FROM profiles p
                  WHERE p.nickname = :nickname) recent
            WHERE u.id = recent.user_id
            RETURNING u.id, recent.nickname
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameUsersFromProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(new QueryParameter(1, "nickname", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(
            """
                UPDATE users u
                SET name = recent.nickname
                FROM (SELECT p.user_id, p.nickname
                      FROM profiles p
                      WHERE p.nickname = ?) recent
                WHERE u.id = recent.user_id
                RETURNING u.id, recent.nickname
                """,
            model.executableSql()
        );
    }

    /**
     * A {@code USING} source that names a common table expression of the delete
     * reads that expression, so its columns are the body's own columns and the
     * body types its placeholders from them.
     */
    @Test
    void shouldResolveCommonTableExpressionSourceOfADeleteUsingClause() {
        String sql = """
            WITH top_orders AS (SELECT user_id, total
                                FROM orders
                                WHERE total = :total)
            DELETE FROM users u
            USING top_orders
            WHERE top_orders.user_id = u.id
              AND u.id = :id
            RETURNING u.id, top_orders.total
            """;

        QueryModel model = analyzer.analyze(
            new Query("DeleteTopOrderUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "total", ColumnType.DECIMAL),
                new QueryParameter(2, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A join condition inside an {@code UPDATE ... FROM} clause sees only the
     * sources of the comma-separated item it joins onto, up to its own joined
     * source, as PostgreSQL scopes it, so neither the target nor an earlier
     * comma-separated source is a known qualifier there.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "UPDATE users u SET name = $1 FROM profiles p JOIN orders o ON o.user_id = u.id"
                + " WHERE u.id = p.user_id|u",
            "UPDATE users u SET name = $1 FROM profiles p, orders o"
                + " JOIN users x ON x.id = p.user_id WHERE u.id = o.user_id|p"
        }
    )
    void shouldRejectAnUpdateFromJoinConditionOutsideItsJoinGroup(String sql, String qualifier) {
        Query query = new Query("RenameUsers", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Unknown source qualifier in query: " + qualifier,
            exception.getMessage()
        );
    }

    /**
     * A returned column of a joined write takes the nullability of its schema
     * column, and every column of a left-joined source is nullable, because an
     * unmatched row reads it as {@code NULL}.
     */
    @Test
    void shouldResolveNullabilityOfJoinedWriteReturningColumns() {
        String sql = """
            UPDATE users u
            SET name = :name
            FROM profiles p
            LEFT JOIN orders o ON o.user_id = p.user_id
            WHERE p.user_id = u.id
            RETURNING u.id, u.name, o.id AS order_id, o.total
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameProfiledUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("order_id", ColumnType.BIGINT, true),
                new QueryColumn("total", ColumnType.DECIMAL, true)
            ),
            model.columns()
        );
    }

    /**
     * {@code RETURNING <target>.*} returns the complete row of the write
     * target, so it carries the target table's shared row identity on a joined
     * and on a single-table insert, update, and delete alike.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "INSERT INTO users AS u (id, name) VALUES ($1, $2) RETURNING u.*",
            "UPDATE users u SET name = $1 WHERE u.id = $2 RETURNING u.*",
            "DELETE FROM users u WHERE u.id = $1 RETURNING u.*",
            "UPDATE users u SET name = $1 FROM profiles p WHERE p.user_id = u.id RETURNING u.*",
            "DELETE FROM users u USING profiles p WHERE p.user_id = u.id RETURNING u.*"
        }
    )
    void shouldResolveRowTableForATargetWildcardOfAWrite(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("WriteUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals("users", model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A target wildcard written beside another returned column embeds the
     * target's row in its own position, which is a result specific to the query
     * rather than the target's shared row.
     */
    @Test
    void shouldNotResolveRowTableForATargetWildcardBesideAnotherColumn() {
        String sql = """
            UPDATE users u
            SET name = :name
            FROM profiles p
            WHERE p.user_id = u.id
            RETURNING u.*, p.nickname
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameProfiledUsers", QueryType.MANY, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                usersRow("u"),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A bare target wildcard of a single-table write written beside another
     * returned column embeds the target's row the same way, named after the
     * name the write exposes for its target.
     */
    @Test
    void shouldEmbedTheTargetRowOfABareReturningWildcardBesideAnotherColumn() {
        String sql = "UPDATE users SET name = :name WHERE id = :id RETURNING *, id";

        QueryModel model = analyzer.analyze(
            new Query("RenameUser", QueryType.ONE, sql),
            parser.parse(sql),
            joinSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                usersRow("users"),
                new QueryColumn("id", ColumnType.BIGINT, false)
            ),
            model.columns()
        );
    }

    /**
     * The only complete row a joined write returns is its target's, so a bare
     * {@code *} and a wildcard of another source are rejected naming what to
     * write instead, and a wildcard carries no alias.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "UPDATE users u SET name = $1 FROM profiles p WHERE p.user_id = u.id RETURNING *"
                + "|RETURNING * is not supported with UPDATE ... FROM or DELETE ... USING;"
                + " write RETURNING u.* for the target's row.",
            "DELETE FROM users USING profiles p WHERE p.user_id = users.id RETURNING *"
                + "|RETURNING * is not supported with UPDATE ... FROM or DELETE ... USING;"
                + " write RETURNING users.* for the target's row.",
            "UPDATE users u SET name = $1 FROM profiles p WHERE p.user_id = u.id RETURNING p.*"
                + "|RETURNING p.* is not supported; return the columns of p by name.",
            "DELETE FROM users u USING profiles p WHERE p.user_id = u.id RETURNING p.*"
                + "|RETURNING p.* is not supported; return the columns of p by name.",
            "UPDATE users u SET name = $1 FROM profiles p WHERE p.user_id = u.id RETURNING u.* AS x"
                + "|RETURNING u.* must not be aliased."
        }
    )
    void shouldRejectAnUnsupportedJoinedWriteReturningWildcard(String sql, String message) {
        Query query = new Query("WriteUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A returned wildcard names one of the write's sources, so an unknown
     * qualifier, which the {@code old} and {@code new} row aliases of
     * PostgreSQL 18 are among them, is rejected as any other unknown source
     * qualifier is.
     */
    @Test
    void shouldRejectAnUnknownReturningWildcardQualifier() {
        String sql = "UPDATE users u SET name = $1 FROM profiles p"
            + " WHERE p.user_id = u.id RETURNING old.*";

        Query query = new Query("RenameUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Unknown source qualifier in query: old",
            exception.getMessage()
        );
    }

    /**
     * The sources of a joined write expose distinct names, the target's name
     * included, because a repeated name would make every reference to it
     * ambiguous.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "UPDATE users u SET name = $1 FROM profiles u WHERE u.user_id = $2|u",
            "DELETE FROM users u USING profiles p, orders p WHERE p.user_id = u.id|p"
        }
    )
    void shouldRejectARepeatedJoinedWriteSourceName(String sql, String name) {
        Query query = new Query("WriteUsers", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "Duplicate source name in query: " + name,
            exception.getMessage()
        );
    }

    /**
     * The placeholders of a joined write bind in the textual order of the
     * query, across its {@code SET} assignments, its {@code FROM} sources, and
     * its {@code WHERE} predicate, whatever their indexes are.
     */
    @Test
    void shouldBindJoinedUpdateParametersInTextualOrder() {
        String sql = """
            UPDATE users u
            SET name = $2
            FROM (SELECT user_id
                  FROM profiles
                  WHERE nickname = $3) recent
            WHERE u.id = recent.user_id
              AND u.id = $1
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameRecentUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "id", ColumnType.BIGINT),
                new QueryParameter(2, "name", ColumnType.VARCHAR),
                new QueryParameter(3, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(2, 3, 1), model.bindingParameterIndexes());
    }

    /**
     * One named placeholder written in a joined write's {@code SET} clause and
     * in its {@code WHERE} predicate is one parameter, bound at each of its
     * textual positions, beside the parameter a join condition types.
     */
    @Test
    void shouldShareOneNamedParameterAcrossAJoinedUpdate() {
        String sql = """
            UPDATE users u
            SET name = :nickname
            FROM profiles p
            JOIN orders o ON o.user_id = p.user_id
                AND o.total = :total
            WHERE p.nickname = :nickname
              AND u.id = :id
            """;

        QueryModel model = analyzer.analyze(
            new Query("RenameOrderingUsers", QueryType.EXEC, sql),
            parser.parse(sql),
            joinSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "nickname", ColumnType.VARCHAR),
                new QueryParameter(2, "total", ColumnType.DECIMAL),
                new QueryParameter(3, "id", ColumnType.BIGINT)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 1, 3), model.bindingParameterIndexes());
    }

    /**
     * Each set operator, written with and without {@code ALL}, combines the
     * branches into one result whose column takes the leftmost branch's name,
     * the type both branches state, and the nullability of its own operator: a
     * {@code UNION} column reads {@code NULL} where either branch's does, an
     * {@code INTERSECT} column only where every branch's does, and an
     * {@code EXCEPT} column where its left side's does, because it returns rows
     * of that side alone.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "UNION|id|parent_id|true",
            "UNION ALL|id|parent_id|true",
            "UNION|parent_id|id|true",
            "UNION ALL|id|id|false",
            "INTERSECT|id|parent_id|false",
            "INTERSECT ALL|parent_id|id|false",
            "INTERSECT|parent_id|parent_id|true",
            "EXCEPT|id|parent_id|false",
            "EXCEPT ALL|parent_id|id|true"
        }
    )
    void shouldAnalyzeEachSetOperator(
        String operator,
        String first,
        String second,
        boolean nullable
    ) {
        String sql = "SELECT %s AS value FROM users %s SELECT %s FROM users"
            .formatted(first, operator, second);

        QueryModel model = analyzer.analyze(
            new Query("ListValues", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals("users", model.table());

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.BIGINT, nullable)),
            model.columns()
        );

        assertNull(model.rowTable());

        assertTrue(model.parameters().isEmpty());
    }

    /**
     * {@code INTERSECT} binds more tightly than {@code UNION} and
     * {@code EXCEPT}, as it does in PostgreSQL, so consecutive
     * {@code INTERSECT} branches are one group before the remaining operators
     * fold left to right. A plain left-to-right fold of
     * {@code A UNION B INTERSECT C} would read the first row as not nullable.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "parent_id|UNION|parent_id|INTERSECT|id|true",
            "id|UNION|parent_id|INTERSECT|parent_id|true",
            "id|UNION|id|INTERSECT|parent_id|false",
            "parent_id|EXCEPT|parent_id|INTERSECT|id|true",
            "id|EXCEPT|parent_id|INTERSECT|parent_id|false"
        }
    )
    void shouldGroupIntersectBranchesBeforeTheOtherOperators(
        String first,
        String firstOperator,
        String second,
        String secondOperator,
        String third,
        boolean nullable
    ) {
        String sql = "SELECT %s AS value FROM users %s SELECT %s FROM users %s SELECT %s FROM users"
            .formatted(first, firstOperator, second, secondOperator, third);

        QueryModel model = analyzer.analyze(
            new Query("ListValues", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.BIGINT, nullable)),
            model.columns()
        );
    }

    /**
     * Each column of a set operation takes the type PostgreSQL 16 resolves the
     * branches' types to where sqlcj states it exactly: two scalar numeric
     * types and two scalar date and time types resolve to the wider, in either
     * branch order, and two scalar character types resolve to the first
     * branch's, blank-padded or not, because each converts implicitly to the
     * other.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "score|visits|INTEGER",
            "visits|parent_id|BIGINT",
            "parent_id|balance|DECIMAL",
            "balance|ratio|REAL",
            "ratio|weight|DOUBLE_PRECISION",
            "weight|score|DOUBLE_PRECISION",
            "score|weight|DOUBLE_PRECISION",
            "visits|score|INTEGER",
            "birth_date|created_at|TIMESTAMP",
            "created_at|updated_at|TIMESTAMP_WITH_TIME_ZONE",
            "updated_at|birth_date|TIMESTAMP_WITH_TIME_ZONE",
            "created_at|birth_date|TIMESTAMP",
            "name|bio|VARCHAR",
            "bio|name|TEXT",
            "code|bio|VARCHAR",
            "name|name|VARCHAR"
        }
    )
    void shouldResolveSetOperationColumnTypeAcrossBranches(
        String first,
        String second,
        ColumnType type
    ) {
        String sql = "SELECT %s AS value FROM users UNION SELECT %s FROM users"
            .formatted(first, second);

        QueryModel model = analyzer.analyze(
            new Query("ListValues", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(new QueryColumn("value", type, true)),
            model.columns()
        );
    }

    /**
     * One type both branches state resolves to itself, its enum type and its
     * array spelling included, which no widening rule reads.
     */
    @Test
    void shouldResolveOneSetOperationColumnTypeToItself() {
        String enumSql = "SELECT role AS value FROM users UNION SELECT role FROM users";

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.ENUM, true, "user_role")),
            analyzer.analyze(
                new Query("ListValues", QueryType.MANY, enumSql),
                parser.parse(enumSql),
                setOperationSchema
            ).columns()
        );

        String arraySql = "SELECT labels AS value FROM users INTERSECT SELECT labels FROM users";

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.VARCHAR, true, null, true)),
            analyzer.analyze(
                new Query("ListValues", QueryType.MANY, arraySql),
                parser.parse(arraySql),
                setOperationSchema
            ).columns()
        );

        String enumArraySql = "SELECT past_roles AS value FROM users"
            + " UNION SELECT past_roles FROM users";

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.ENUM, true, "user_role", true)),
            analyzer.analyze(
                new Query("ListValues", QueryType.MANY, enumArraySql),
                parser.parse(enumArraySql),
                setOperationSchema
            ).columns()
        );
    }

    /**
     * A column whose branches state types this subset resolves to no single
     * type is rejected naming the column, both types as PostgreSQL spells them,
     * and the cast that states one type for every branch, because an inferred
     * type that is not PostgreSQL's own would model the result wrongly.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "score|active|smallint|boolean",
            "visits|external_id|integer|uuid",
            "parent_id|name|bigint|varchar",
            "balance|bio|numeric|text",
            "ratio|avatar|real|bytea",
            "weight|payload|double precision|json",
            "birth_date|document|date|jsonb",
            "opened_at|created_at|time|timestamp",
            "updated_at|labels|timestamptz|varchar[]",
            "code|scores|bpchar|integer[]",
            "role|state|user_role|user_state",
            "past_roles|role|user_role[]|user_role",
            "labels|name|varchar[]|varchar"
        }
    )
    void shouldRejectUnresolvableSetOperationColumnType(
        String first,
        String second,
        String firstType,
        String secondType
    ) {
        String sql = "SELECT %s AS value FROM users UNION SELECT %s FROM users"
            .formatted(first, second);
        Query query = new Query("ListValues", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals(
            "Set operation column value has types %s and %s;"
                .formatted(firstType, secondType)
                + " cast it to one type in every branch, such as value::" + firstType,
            exception.getMessage()
        );
    }

    /**
     * The branches of a set operation pair their columns by position, so each
     * must select the number of columns the leftmost branch selects, and a
     * wildcard counts as the columns it expands to. The rejection names the
     * first branch that differs.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT id, name FROM users UNION SELECT id FROM users|2|2|1",
            "SELECT id FROM users UNION SELECT id FROM users UNION SELECT id, name FROM users|1|3|2",
            "SELECT * FROM profiles EXCEPT SELECT id FROM profiles|3|2|1"
        }
    )
    void shouldRejectDifferentSetOperationColumnCounts(
        String sql,
        int expected,
        int branch,
        int selected
    ) {
        Query query = new Query("ListValues", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals(
            "Every branch of a set operation must select the same number of columns;"
                + " the first selects %d and branch %d selects %d.".formatted(
                    expected,
                    branch,
                    selected
                ),
            exception.getMessage()
        );
    }

    /** {@code MINUS}, which PostgreSQL does not have, names {@code EXCEPT}. */
    @Test
    void shouldRejectMinus() {
        String sql = "SELECT id FROM users MINUS SELECT id FROM profiles";
        Query query = new Query("ListValues", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals("MINUS is not supported; write EXCEPT.", exception.getMessage());
    }

    /**
     * The leftmost branch names the result columns, so a cast and a typed
     * aggregate of any other branch may omit the alias each requires of a read.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT name AS label FROM users UNION SELECT 'anonymous'::text FROM users"
                + "|label|VARCHAR|true",
            "SELECT visits AS total FROM users UNION SELECT COUNT(*) FROM users"
                + "|total|BIGINT|true",
            "SELECT visits AS total FROM users UNION SELECT MAX(score) FROM users"
                + "|total|INTEGER|true"
        }
    )
    void shouldAcceptAnUnaliasedCastOrAggregateOutsideTheLeftmostBranch(
        String sql,
        String name,
        ColumnType type,
        boolean nullable
    ) {
        QueryModel model = analyzer.analyze(
            new Query("ListValues", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(new QueryColumn(name, type, nullable)),
            model.columns()
        );
    }

    /**
     * The leftmost branch names the result columns, so its own cast and
     * aggregate still require an alias.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT 'anonymous'::text FROM users UNION SELECT name AS label FROM users"
                + "|A cast projection requires a result alias,"
                + " such as SUM(amount)::numeric AS total.",
            "SELECT COUNT(*) FROM users UNION SELECT visits AS total FROM users"
                + "|COUNT(*) requires a result alias, such as COUNT(*) AS total."
        }
    )
    void shouldRequireAResultAliasInTheLeftmostBranch(String sql, String message) {
        Query query = new Query("ListValues", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals(message, exception.getMessage());
    }

    /**
     * A set operation whose every branch returns one table's complete row
     * returns that table's shared row record, so the whole-row reads of a
     * package stay one result type.
     */
    @Test
    void shouldReturnTheSharedRowRecordWhenEveryBranchReturnsOneTablesRow() {
        String sql = "SELECT * FROM profiles UNION SELECT * FROM profiles WHERE id > $1";

        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals("profiles", model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A set operation any branch of which returns a result of its own returns
     * flat columns, and a wildcard beside another item embeds no row there,
     * because a set operation's column belongs to no one source.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT * FROM profiles UNION SELECT id, user_id, nickname FROM profiles",
            "SELECT id, user_id, nickname FROM profiles UNION SELECT * FROM profiles"
        }
    )
    void shouldReturnFlatColumnsWhenABranchReturnsItsOwnResult(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    /**
     * A wildcard written beside another item embeds one row of its source in a
     * read, while a set operation resolves the columns the wildcard expands to,
     * so the result is flat.
     */
    @Test
    void shouldResolveTheExpandedColumnsOfAWildcardBesideAnotherItem() {
        String sql = """
            SELECT p.*,
                   0::bigint AS extra
            FROM profiles p
            UNION
            SELECT p.*,
                   p.user_id
            FROM profiles p
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListProfiles", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertNull(model.rowTable());

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("extra", ColumnType.BIGINT, true)
            ),
            model.columns()
        );
    }

    /**
     * The placeholders of every branch are typed by that branch's own clause
     * rules and bind in the textual order the parser reported, and a trailing
     * {@code LIMIT} and {@code OFFSET} page the whole result, which the parser
     * reports on the last branch without an {@code ORDER BY} and on the set
     * operation itself with one.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            """
                SELECT id FROM users WHERE name = $1
                UNION
                SELECT id FROM profiles WHERE nickname = $2
                LIMIT $3 OFFSET $4
                """,
            """
                SELECT id FROM users WHERE name = $1
                UNION
                SELECT id FROM profiles WHERE nickname = $2
                ORDER BY id
                LIMIT $3 OFFSET $4
                """
        }
    )
    void shouldBindSetOperationParametersInTextualOrder(String sql) {
        QueryModel model = analyzer.analyze(
            new Query("ListIds", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR),
                new QueryParameter(3, "limit", ColumnType.INTEGER),
                new QueryParameter(4, "offset", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3, 4), model.bindingParameterIndexes());

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );
    }

    /**
     * One named placeholder written in two branches is one parameter, bound at
     * each of its textual positions, as it is inside one {@code SELECT}.
     */
    @Test
    void shouldShareOneNamedParameterAcrossSetOperationBranches() {
        String sql = """
            SELECT id FROM users WHERE name = :handle
            UNION ALL
            SELECT id FROM profiles WHERE nickname = :handle
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListIds", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(new QueryParameter(1, "handle", ColumnType.VARCHAR)),
            model.parameters()
        );

        assertEquals(List.of(1, 1), model.bindingParameterIndexes());
    }

    /**
     * An {@code ORDER BY} of a set operation's result may name a result column,
     * compared ignoring case, or a position, and every other item reaches the
     * database as written.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ORDER BY value",
            "ORDER BY VALUE",
            "ORDER BY value DESC",
            "ORDER BY 1",
            "ORDER BY lower(value)"
        }
    )
    void shouldAcceptASetOperationOrderingOfItsResultColumns(String ordering) {
        String sql = "SELECT name AS value FROM users UNION SELECT nickname FROM profiles "
            + ordering;

        QueryModel model = analyzer.analyze(
            new Query("ListValues", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.VARCHAR, true)),
            model.columns()
        );
    }

    /**
     * An {@code ORDER BY} column of a set operation's result that names no
     * result column, a qualified reference to one included, is rejected as
     * PostgreSQL restricts such an ordering, naming the item as written and a
     * result column to order by.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "ORDER BY name|name",
            "ORDER BY name DESC|name DESC",
            "ORDER BY users.value|users.value"
        }
    )
    void shouldRejectASetOperationOrderingOutsideItsResultColumns(
        String ordering,
        String written
    ) {
        String sql = "SELECT name AS value FROM users UNION SELECT nickname FROM profiles "
            + ordering;
        Query query = new Query("ListValues", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals(
            "ORDER BY %s must name a result column of the set operation, such as ORDER BY value."
                .formatted(written),
            exception.getMessage()
        );
    }

    /**
     * A {@code WITH} clause written before a set operation belongs to the whole
     * list, so every branch reads it, and its own body's placeholders are typed
     * by its clause rules.
     */
    @Test
    void shouldAnalyzeACommonTableExpressionBeforeASetOperation() {
        String sql = """
            WITH recent AS (SELECT id, nickname
                            FROM profiles
                            WHERE nickname = $1)
            SELECT id
            FROM recent
            UNION
            SELECT id
            FROM recent
            WHERE nickname = $2
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListRecentIds", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals("recent", model.table());

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "nickname", ColumnType.VARCHAR),
                new QueryParameter(2, "nickname", ColumnType.VARCHAR)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2), model.bindingParameterIndexes());
    }

    /**
     * A branch written in parentheses is the query it encloses: a set operation
     * of its own, which states the grouping the parentheses write, or a plain
     * {@code SELECT} with a {@code WITH} list of its own. A parenthesized
     * top-level set operation is the set operation it encloses, as a
     * parenthesized read is the read it encloses.
     */
    @Test
    void shouldAnalyzeAParenthesizedSetOperationBranch() {
        String nested = """
            SELECT id AS value
            FROM users
            UNION
            (SELECT parent_id FROM users INTERSECT SELECT parent_id FROM users)
            """;

        assertEquals(
            List.of(new QueryColumn("value", ColumnType.BIGINT, true)),
            analyzer.analyze(
                new Query("ListValues", QueryType.MANY, nested),
                parser.parse(nested),
                setOperationSchema
            ).columns()
        );

        String withBranch = """
            SELECT id
            FROM users
            UNION
            (WITH recent AS (SELECT id FROM profiles) SELECT id FROM recent)
            """;

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            analyzer.analyze(
                new Query("ListIds", QueryType.MANY, withBranch),
                parser.parse(withBranch),
                setOperationSchema
            ).columns()
        );

        String parenthesized = "(SELECT id FROM users UNION SELECT parent_id FROM users)";

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, true)),
            analyzer.analyze(
                new Query("ListIds", QueryType.MANY, parenthesized),
                parser.parse(parenthesized),
                setOperationSchema
            ).columns()
        );
    }

    /**
     * A nested set operation written in parentheses carries the
     * {@code ORDER BY}, {@code LIMIT}, and {@code OFFSET} the parser reports on
     * it, so its own result column check and its own pagination rules apply to
     * them, beside the parameters the other branches type, and every parameter
     * binds in the textual order the parser reported.
     */
    @Test
    void shouldResolveTheOwnClausesOfANestedSetOperationBranch() {
        String sql = """
            SELECT id FROM users WHERE name = $1
            UNION
            (SELECT id FROM profiles
             UNION
             SELECT user_id FROM profiles
             ORDER BY id
             LIMIT $2 OFFSET $3)
            """;

        QueryModel model = analyzer.analyze(
            new Query("ListIds", QueryType.MANY, sql),
            parser.parse(sql),
            setOperationSchema
        );

        assertEquals(
            List.of(
                new QueryParameter(1, "name", ColumnType.VARCHAR),
                new QueryParameter(2, "limit", ColumnType.INTEGER),
                new QueryParameter(3, "offset", ColumnType.INTEGER)
            ),
            model.parameters()
        );

        assertEquals(List.of(1, 2, 3), model.bindingParameterIndexes());

        assertEquals(
            List.of(new QueryColumn("id", ColumnType.BIGINT, false)),
            model.columns()
        );
    }

    /**
     * An {@code ORDER BY} column of a nested set operation that names no result
     * column of that nested list is rejected as one of the top-level list is,
     * naming the item as written and a result column of the nested list to
     * order by.
     */
    @Test
    void shouldRejectANestedSetOperationOrderingOutsideItsResultColumns() {
        String sql = """
            SELECT name AS value FROM users
            UNION
            (SELECT nickname FROM profiles
             UNION
             SELECT nickname FROM profiles
             ORDER BY name)
            """;
        Query query = new Query("ListValues", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals(
            "ORDER BY name must name a result column of the set operation,"
                + " such as ORDER BY nickname.",
            exception.getMessage()
        );
    }

    /**
     * A set operation produces rows, so it must be declared with a result
     * annotation as every other read is.
     */
    @Test
    void shouldRejectASetOperationWithoutResultQueryType() {
        String sql = "SELECT id FROM users UNION SELECT id FROM profiles";
        Query query = new Query("ListIds", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, setOperationSchema)
        );

        assertEquals(
            "SELECT queries must be declared as :one, :optional, or :many",
            exception.getMessage()
        );
    }
}
