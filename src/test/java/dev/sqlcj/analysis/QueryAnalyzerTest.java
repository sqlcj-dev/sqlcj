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
                        new QueryParameter(1, ColumnType.BIGINT)
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
                        new QueryParameter(1, ColumnType.BIGINT),
                        new QueryParameter(2, ColumnType.VARCHAR)
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
                        new QueryParameter(1, ColumnType.BIGINT)
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
                        new QueryParameter(1, ColumnType.BIGINT),
                        new QueryParameter(2, ColumnType.VARCHAR)
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
}
