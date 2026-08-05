package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.sql.SqlParser;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerTest {

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
        QueryModel model = analyzer.analyze(query, statement);

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
        QueryModel model = analyzer.analyze(query, statement);

        assertEquals("users", model.table());
        assertEquals(List.of(1), model.parameters());
    }

    @Test
    void shouldAnalyzeSelectWithMultipleParameters() {
        Query query = new Query(
                "ListUsersByIdAndUsername",
                QueryType.MANY,
                """
                SELECT *
                FROM users
                WHERE id = $1
                  AND username = $2;
                """
        );

        Statement statement = parser.parse(query.sql());
        QueryModel model = analyzer.analyze(query, statement);

        assertEquals("users", model.table());
        assertEquals(List.of(1, 2), model.parameters());
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
                        () -> analyzer.analyze(query, statement)
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
                        () -> analyzer.analyze(query, statement)
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
                        () -> analyzer.analyze(query, statement)
                );

        assertEquals(
                "DELETE is not supported yet",
                exception.getMessage()
        );
    }
}
