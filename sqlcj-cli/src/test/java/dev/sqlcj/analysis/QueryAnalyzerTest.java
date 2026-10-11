package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.EnumType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import dev.sqlcj.sql.ParsedSql;
import dev.sqlcj.sql.SqlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT COUNT(*) FROM users"
                + "|COUNT(*) requires a result alias, such as COUNT(*) AS total.",
            "SELECT name, COUNT(*) FROM users GROUP BY name"
                + "|COUNT(*) requires a result alias, such as COUNT(*) AS total.",
            "SELECT COUNT(id) AS n FROM users"
                + "|Cannot infer the type of COUNT(id) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT COUNT(DISTINCT id) AS n FROM users"
                + "|Cannot infer the type of COUNT(DISTINCT id) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT COUNT(u.*) AS n FROM users u"
                + "|Cannot infer the type of COUNT(u.*) in SELECT;"
                + " state it with a cast and an alias,"
                + " such as SUM(amount)::numeric AS total",
            "SELECT pg_catalog.count(*) AS n FROM users"
                + "|Cannot infer the type of pg_catalog.count(*) in SELECT;"
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
                + " such as SUM(amount)::numeric AS total"
        }
    )
    void shouldRejectUnsupportedCountProjectionForm(String sql, String message) {
        Query query = new Query("CountUsers", QueryType.ONE, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
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

    @Test
    void shouldExpandAllColumnsOfLeftJoinedSourceAsNullable() {
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
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, true),
                new QueryColumn("user_id", ColumnType.BIGINT, true),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

    @Test
    void shouldExpandQualifiedAllColumnsOfLeftJoinedSourceAsNullable() {
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
                new QueryColumn("id", ColumnType.BIGINT, true),
                new QueryColumn("user_id", ColumnType.BIGINT, true),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
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

        assertEquals(
            List.of(
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("name", ColumnType.VARCHAR, true),
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true)
            ),
            model.columns()
        );
    }

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
                new QueryColumn("id", ColumnType.BIGINT, false),
                new QueryColumn("user_id", ColumnType.BIGINT, false),
                new QueryColumn("nickname", ColumnType.VARCHAR, true),
                new QueryColumn("name", ColumnType.VARCHAR, true)
            ),
            model.columns()
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

    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT * FROM users u JOIN profiles p ON p.user_id = u.id",
            "SELECT u.* FROM users u JOIN profiles p ON p.user_id = u.id"
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
     * A cast types a placeholder inside a subquery at any depth, in a read and
     * in a write, even though the subquery itself is not analyzed. Such a
     * placeholder is named after its own placeholder, because no analyzed
     * column names it.
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
     * A {@code WITH} body is carried unanalyzed, so its cast placeholder is
     * typed by that cast alone and binds before the analyzed clauses that
     * follow it.
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
                new QueryParameter(1, "param1", ColumnType.DECIMAL),
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
     * expression or clause it stands in.
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
            "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.id = :id)"
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
            "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM tasks t WHERE t.x = :x)"
                + "|:x|t.x = :x",
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
            "INSERT INTO users (id) VALUES ($1) RETURNING users.*"
                + "|Only an unqualified RETURNING * is supported.",
            "INSERT INTO users (id) VALUES ($1) RETURNING users.* AS x"
                + "|Only an unqualified RETURNING * is supported.",
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
            "INSERT INTO users (id) SELECT id FROM users RETURNING id"
                + "|INSERT ... SELECT is not supported.",
            "INSERT INTO users (id) (VALUES ($1)) RETURNING id"
                + "|RETURNING requires an INSERT with a single VALUES row.",
            "INSERT INTO users (id) VALUES ($1), ($2) RETURNING id"
                + "|INSERT requires a single VALUES row.",
            "WITH known AS (SELECT id FROM users) INSERT INTO users (id) VALUES ($1) RETURNING id"
                + "|Common table expressions are not supported in a returning write.",
            "UPDATE users SET name = $2 FROM profiles WHERE users.id = $1 RETURNING id"
                + "|UPDATE ... FROM and joined updates are not supported.",
            "DELETE FROM users USING profiles WHERE users.id = $1 RETURNING id"
                + "|DELETE ... USING and joined deletes are not supported."
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
     * Parentheses around a query source state a query all the same, so such an
     * insert stays rejected on an {@code :exec} and on a returning insert
     * alike.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "EXEC|INSERT INTO users (id, name) (SELECT id, name FROM users)",
            "ONE|INSERT INTO users (id, name) (SELECT id, name FROM users) RETURNING id"
        }
    )
    void shouldRejectParenthesizedInsertQuerySource(QueryType type, String sql) {
        Query query = new Query("CopyUsers", type, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals("INSERT ... SELECT is not supported.", exception.getMessage());
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

    /** A row assignment and a multi-row insert stay rejected. */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "UPDATE users SET (name, active) = ('a', true)|"
                + "UPDATE assignments must set one column at a time.",
            "INSERT INTO users (id, name) VALUES (1, 'a'), (2, 'b')|"
                + "INSERT requires a single VALUES row."
        }
    )
    void shouldRejectExcludedWriteValueForm(String sql, String message) {
        Query query = new Query("TouchUsers", QueryType.EXEC, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, writeSchema)
        );

        assertEquals(message, exception.getMessage());
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
}
