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

        QueryParameter parameter = model.parameters().getFirst();

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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter parameter = model.parameters().getFirst();

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

        QueryParameter parameter = model.parameters().getFirst();

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
     * A cast does not make a projected placeholder analyzable, so it stays
     * rejected by the placeholder accounting in either spelling.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = {
            "SELECT $1::int AS x FROM users|[1]",
            "SELECT :n::int AS x FROM users|[:n]"
        }
    )
    void shouldRejectCastPlaceholderProjection(String sql, String compiled) {
        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "SQL placeholders %s are not the analyzed parameters []; "
                .formatted(compiled)
                + "a placeholder is in an unsupported location",
            exception.getMessage()
        );
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
                + "|Unsupported SELECT expression: Function",
            "SELECT COUNT(DISTINCT id) AS n FROM users"
                + "|Unsupported SELECT expression: Function",
            "SELECT COUNT(u.*) AS n FROM users u"
                + "|Unsupported SELECT expression: Function",
            "SELECT pg_catalog.count(*) AS n FROM users"
                + "|Unsupported SELECT expression: Function",
            "SELECT lower(name) AS n FROM users"
                + "|Unsupported SELECT expression: Function",
            "SELECT COUNT(*) FILTER (WHERE id > 1) AS n FROM users"
                + "|Unsupported SELECT expression: AnalyticExpression",
            "SELECT COUNT(*) OVER () AS n FROM users"
                + "|Unsupported SELECT expression: AnalyticExpression"
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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

        QueryParameter first = model.parameters().getFirst();
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
     * {@code isLeft()}.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "RIGHT JOIN profiles p ON p.user_id = u.id",
            "RIGHT OUTER JOIN profiles p ON p.user_id = u.id",
            "FULL OUTER JOIN profiles p ON p.user_id = u.id",
            "OUTER JOIN profiles p ON p.user_id = u.id",
            "NATURAL LEFT JOIN profiles p",
            "LEFT SEMI JOIN profiles p ON p.user_id = u.id",
            "LEFT JOIN profiles p USING (user_id)"
        }
    )
    void shouldRejectUnsupportedJoinModifier(String joinClause) {
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
            "Only unmodified INNER JOIN and LEFT JOIN clauses are supported.",
            exception.getMessage()
        );
    }

    /** A left join keeps the inner join's {@code ON} equality requirement. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ON p.user_id = u.id AND p.nickname = u.name",
            "ON p.user_id > u.id"
        }
    )
    void shouldRejectLeftJoinWithoutSingleQualifiedEquality(String onClause) {
        String sql = """
            SELECT u.id
            FROM users u
            LEFT JOIN profiles p %s
            """.formatted(onClause);

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );

        assertEquals(
            "A join requires exactly one ON equality.",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectJoinWithoutSingleQualifiedEquality() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = u.id AND p.nickname = u.name
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
        );
    }

    @Test
    void shouldRejectJoinConditionWithoutEarlierSource() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN profiles p ON p.user_id = p.id
            """;

        Query query = new Query("ListIds", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, joinSchema)
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
            "SQL placeholders [1, 2] are not the analyzed parameters [1]; "
                + "a placeholder is in an unsupported location",
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
     * reported by the placeholder accounting, which writes the placeholder as
     * the query does.
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
            "SQL placeholders [:name] are not the analyzed parameters []; "
                + "a placeholder is in an unsupported location",
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
     * A cast does not make a placeholder analyzable in a clause this analyzer
     * does not traverse, so such a placeholder stays rejected by the
     * placeholder accounting.
     */
    @Test
    void shouldRejectCastPlaceholderInUnanalyzedLocation() {
        String sql = "SELECT id FROM users WHERE id = $1 ORDER BY $2::int";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "SQL placeholders [1, 2] are not the analyzed parameters [1]; "
                + "a placeholder is in an unsupported location",
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

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "INSERT INTO users (id) VALUES ($1) RETURNING id AS user_id"
                + "|RETURNING items must not be aliased.",
            "INSERT INTO users (id) VALUES ($1) RETURNING id + 1"
                + "|Unsupported RETURNING expression: Addition",
            "INSERT INTO users (id) VALUES ($1) RETURNING users.*"
                + "|Only an unqualified RETURNING * is supported.",
            "DELETE FROM users WHERE id = $1 RETURNING count(id)"
                + "|Unsupported RETURNING expression: Function"
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
            "INSERT INTO users (id) VALUES ($1) ON CONFLICT DO NOTHING RETURNING id"
                + "|INSERT ... ON CONFLICT is not supported.",
            "INSERT INTO users (id) SELECT id FROM users RETURNING id"
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
     * placeholder unanalyzed, which the placeholder accounting rejects.
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
            "SQL placeholders [1, 2] are not the analyzed parameters [1];"
                + " a placeholder is in an unsupported location",
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
            "SQL placeholders [:n, :id] are not the analyzed parameters [:id];"
                + " a placeholder is in an unsupported location",
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
}
