package dev.sqlcj.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryParserTest {

    private final QueryParser parser = new DefaultQueryParser();

    @Test
    void parsesSingleQuery() {
        String source = """
                -- name: GetUser :one
                SELECT *
                FROM users
                WHERE id = $1;
                """;

        List<Query> queries = parser.parse(source);

        assertEquals(1, queries.size());

        Query query = queries.getFirst();

        assertEquals("GetUser", query.name());
        assertEquals(QueryType.ONE, query.type());
        assertEquals("""
                SELECT *
                FROM users
                WHERE id = $1;
                """.trim(), query.sql());
    }

    @Test
    void parsesMultipleQueries() {
        String source = """
                -- name: GetUser :one
                SELECT *
                FROM users
                WHERE id = $1;
                
                -- name: ListUsers :many
                SELECT *
                FROM users;
                """;

        List<Query> queries = parser.parse(source);

        assertEquals(2, queries.size());

        assertEquals("GetUser", queries.getFirst().name());
        assertEquals(QueryType.ONE, queries.getFirst().type());

        Query second = queries.get(1);

        assertEquals("ListUsers", second.name());
        assertEquals(QueryType.MANY, second.type());
    }

    @Test
    void parsesExecQueryType() {
        String source = """
                -- name: DeleteUser :exec
                DELETE FROM users
                WHERE id = $1;
                """;

        List<Query> queries = parser.parse(source);

        assertEquals(1, queries.size());
        assertEquals(QueryType.EXEC, queries.getFirst().type());
    }

    @Test
    void rejectsInvalidQueryType() {
        String source = """
                -- name: GetUser :invalid
                SELECT *
                FROM users;
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source)
        );

        assertEquals(
                "Unknown query type: :invalid",
                exception.getMessage()
        );
    }

    @Test
    void rejectsDuplicateQueryNames() {
        String source = """
                -- name: GetUser :one
                SELECT *
                FROM users
                WHERE id = $1;
                
                -- name: GetUser :many
                SELECT *
                FROM users;
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source)
        );

        assertEquals(
                "Duplicate query: GetUser",
                exception.getMessage()
        );
    }

    @Test
    void rejectsQueryWithoutSql() {
        String source = """
                -- name: GetUser :one
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source)
        );

        assertEquals(
                "Query 'GetUser' has no SQL",
                exception.getMessage()
        );
    }

    @Test
    void returnsEmptyListWhenNoQueriesExist() {
        String source = """
                SELECT *
                FROM users;
                """;

        List<Query> queries = parser.parse(source);

        assertTrue(queries.isEmpty());
    }

    @Test
    void rejectsInvalidHeader() {
        String source = """
                -- name:
                SELECT *
                FROM users;
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(source)
        );

        assertEquals(
                "Invalid query header: -- name:",
                exception.getMessage()
        );
    }

    @Test
    void preservesQueryOrder() {
        String source = """
            -- name: First :one
            SELECT 1;
            
            -- name: Second :one
            SELECT 2;
            
            -- name: Third :one
            SELECT 3;
            """;

        List<Query> queries = parser.parse(source);

        assertEquals(3, queries.size());

        assertEquals("First", queries.get(0).name());
        assertEquals("Second", queries.get(1).name());
        assertEquals("Third", queries.get(2).name());
    }
}
