package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
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
            "SELECT id FROM users WHERE name LIKE :pattern"
                + "|Named parameter ':pattern' is not supported; use an indexed placeholder such as $1"
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
    void shouldRejectNamedRangeBound() {
        String sql = "SELECT id FROM users WHERE id BETWEEN :lo AND :hi";

        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Named parameter ':lo' is not supported; use an indexed placeholder such as $1",
            exception.getMessage()
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

    @ParameterizedTest
    @ValueSource(
        strings = {
            "SELECT id FROM users ORDER BY id LIMIT :n",
            "SELECT id FROM users ORDER BY id OFFSET :n"
        }
    )
    void shouldRejectNamedPaginationValue(String sql) {
        Query query = new Query("ListUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Named parameter ':n' is not supported; use an indexed placeholder such as $1",
            exception.getMessage()
        );
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
    void shouldRejectNamedParameter() {
        String sql = "SELECT * FROM users WHERE id = :userId";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        UnsupportedOperationException exception = assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
        );

        assertEquals(
            "Named parameter ':userId' is not supported; use an indexed placeholder such as $1",
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectNamedParameterInInList() {
        String sql = "SELECT * FROM users WHERE id IN ($1, :other)";

        Query query = new Query("FindUsers", QueryType.MANY, sql);
        ParsedSql parsedSql = parser.parse(sql);

        assertThrows(
            UnsupportedOperationException.class,
            () -> analyzer.analyze(query, parsedSql, schema)
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
}
