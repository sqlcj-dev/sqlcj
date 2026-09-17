package dev.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcQueryExecutorTest {

    private JdbcDataSource dataSource;
    private JdbcQueryExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        executor = new JdbcQueryExecutor(dataSource);

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {

            statement.execute("""
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255),
                    active BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP,
                    balance DECIMAL(10, 2)
                )
                """);

            statement.execute("""
                INSERT INTO users
                    (id, name, active, birth_date, created_at, balance)
                VALUES
                    (1, 'Alice', TRUE, '1990-01-15',
                     '2026-01-01 10:00:00', 100.50),
                    (2, 'Bob', FALSE, '1985-05-20',
                     '2026-02-01 11:30:00', 200.75),
                    (3, 'Charlie', TRUE, '1995-10-10',
                     '2026-03-01 12:45:00', 300.25)
                """);
        }
    }

    @Test
    void shouldBindSingleParameter() {
        Long result = executor.query(
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        assertEquals(1L, result);
    }

    @Test
    void shouldBindParametersInOrder() {
        String result = executor.query(
            """
                SELECT name
                FROM users
                WHERE id = ?
                  AND active = ?
                """,
            List.of(2L, false),
            resultSet -> resultSet.getString("name")
        );

        assertEquals("Bob", result);
    }

    @Test
    void shouldBindSupportedJavaTypes() {
        String result = executor.query(
            """
                SELECT name
                FROM users
                WHERE birth_date = ?
                  AND created_at = ?
                  AND balance = ?
                """,
            List.of(
                LocalDate.of(1990, 1, 15),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                new BigDecimal("100.50")
            ),
            resultSet -> resultSet.getString("name")
        );

        assertEquals("Alice", result);
    }

    @Test
    void shouldReturnAllRowsForQueryMany() {
        List<User> results = executor.queryMany(
            """
                SELECT id, name
                FROM users
                WHERE active = ?
                ORDER BY id
                """,
            List.of(true),
            resultSet -> new User(
                resultSet.getLong("id"),
                resultSet.getString("name")
            )
        );

        assertEquals(
            List.of(
                new User(1L, "Alice"),
                new User(3L, "Charlie")
            ),
            results
        );
    }

    @Test
    void shouldReturnEmptyListWhenQueryManyFindsNoRows() {
        List<User> results = executor.queryMany(
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(999L),
            resultSet -> new User(
                resultSet.getLong("id"),
                resultSet.getString("name")
            )
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldReturnNullWhenQueryFindsNoRows() {
        User result = executor.query(
            """
                SELECT id, name
                FROM users
                WHERE id = ?
                """,
            List.of(999L),
            resultSet -> new User(
                resultSet.getLong("id"),
                resultSet.getString("name")
            )
        );

        assertNull(result);
    }

    @Test
    void shouldReturnAffectedRowCountForExecute() {
        int affected = executor.execute(
            """
                UPDATE users
                SET name = ?
                WHERE id = ?
                """,
            List.of("Alicia", 1L)
        );

        assertEquals(1, affected);

        String name = executor.query(
            "SELECT name FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getString("name")
        );

        assertEquals("Alicia", name);
    }

    @Test
    void shouldWrapSqlExceptionForExecute() {
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.execute(
                "DELETE FROM missing_table",
                List.of()
            )
        );

        assertEquals("Failed to execute query", exception.getMessage());
        assertInstanceOf(SQLException.class, exception.getCause());
    }

    @Test
    void shouldWrapSqlException() {
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.query(
                "SELECT * FROM missing_table",
                List.of(),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals("Failed to execute query", exception.getMessage());
        assertInstanceOf(SQLException.class, exception.getCause());
    }

    private record User(
        long id,
        String name
    ) {
    }
}
