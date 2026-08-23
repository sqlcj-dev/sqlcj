package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import dev.sqlcj.sql.SqlParser;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement, schema);

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

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement, schema);

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

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement, schema);

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
    void shouldRejectInsertStatements() {
        Query query = new Query(
                "InsertUser",
                QueryType.EXEC,
                """
                INSERT INTO users(id)
                VALUES ($1);
                """
        );

        Statement statement = parser.parse(query.sql());

        UnsupportedOperationException exception =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> analyzer.analyze(query, statement, schema)
                );

        assertEquals(
                "INSERT is not supported yet",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUpdateStatements() {
        Query query = new Query(
                "UpdateUser",
                QueryType.EXEC,
                """
                UPDATE users
                SET username = $2
                WHERE id = $1;
                """
        );

        Statement statement = parser.parse(query.sql());

        UnsupportedOperationException exception =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> analyzer.analyze(query, statement, schema)
                );

        assertEquals(
                "UPDATE is not supported yet",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectDeleteStatements() {
        Query query = new Query(
                "DeleteUser",
                QueryType.EXEC,
                """
                DELETE users
                WHERE id = $1;
                """
        );

        Statement statement = parser.parse(query.sql());

        UnsupportedOperationException exception =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> analyzer.analyze(query, statement, schema)
                );

        assertEquals(
                "DELETE is not supported yet",
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        assertThrows(
                IllegalArgumentException.class,
                () -> analyzer.analyze(query, statement, schema)
        );
    }

    @Test
    void shouldThrowWhenColumnDoesNotExist() {
        Query query = new Query(
                "GetUser",
                QueryType.ONE,
                "SELECT username FROM users"
        );

        Statement statement = parser.parse(query.sql());

        assertThrows(
                IllegalArgumentException.class,
                () -> analyzer.analyze(query, statement, schema)
        );
    }

    @Test
    void shouldResolveQueryParameterFromReferencedColumn() {
        Query query = new Query(
                "GetUser",
                QueryType.ONE,
                "SELECT * FROM users WHERE id = $1"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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
    @ValueSource(strings = {
            "=",
            "<>",
            ">",
            ">=",
            "<",
            "<="
    })
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
                """.formatted(operator)
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
                schema
        );

        assertEquals(1, model.parameters().size());

        QueryParameter parameter = model.parameters().getFirst();

        assertEquals(1, parameter.index());
        assertEquals("id", parameter.name());
        assertEquals(ColumnType.BIGINT, parameter.type());
    }

    @Test
    void shouldResolveQueryParametersInsideInExpression() {
        Query query = new Query(
                "FindUsers",
                QueryType.MANY,
                "SELECT * FROM users WHERE id IN ($1, $2, $3)"
        );

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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

        Statement statement = parser.parse(query.sql());

        QueryModel model = analyzer.analyze(
                query,
                statement,
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
    void shouldPreserveQuerySql() {
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
                sql,
                model.sql()
        );
    }
}
