package dev.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcQueryExecutorTest {

    /** The generated repository every operation of these tests reports. */
    private static final String REPOSITORY = "UsersRepository";

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
        Long result = executor.queryOne(
            REPOSITORY,
            "GetUser",
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        assertEquals(1L, result);
    }

    @Test
    void shouldBindParametersInOrder() {
        String result = executor.queryOne(
            REPOSITORY,
            "GetUser",
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
        String result = executor.queryOne(
            REPOSITORY,
            "GetUser",
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
            REPOSITORY,
            "ListUsers",
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
            REPOSITORY,
            "ListUsers",
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
    void shouldReturnTheRowWhenQueryOneFindsExactlyOneRow() {
        User result = executor.queryOne(
            REPOSITORY,
            "GetUser",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(1L),
            userMapper()
        );

        assertEquals(new User(1L, "Alice"), result);
    }

    @Test
    void shouldFailWhenQueryOneFindsNoRow() {
        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id, name FROM users WHERE id = ?",
                List.of(999L),
                userMapper()
            )
        );

        assertEquals(
            "Query 'GetUser' in UsersRepository returned no row; expected exactly one",
            exception.getMessage()
        );

        assertNull(exception.getCause());
        assertInstanceOf(QueryExecutionException.class, exception);
    }

    @Test
    void shouldFailWhenQueryOneFindsMoreThanOneRow() {
        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id, name FROM users WHERE active = ? ORDER BY id",
                List.of(true),
                userMapper()
            )
        );

        assertEquals(
            "Query 'GetUser' in UsersRepository returned more than one row; expected exactly one",
            exception.getMessage()
        );

        assertNull(exception.getCause());
    }

    @Test
    void shouldReturnTheRowWhenQueryOptionalFindsOneRow() {
        Optional<User> result = executor.queryOptional(
            REPOSITORY,
            "FindUser",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(2L),
            userMapper()
        );

        assertEquals(Optional.of(new User(2L, "Bob")), result);
    }

    @Test
    void shouldReturnEmptyOptionalWhenQueryOptionalFindsNoRow() {
        Optional<User> result = executor.queryOptional(
            REPOSITORY,
            "FindUser",
            "SELECT id, name FROM users WHERE id = ?",
            List.of(999L),
            userMapper()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldFailWhenQueryOptionalFindsMoreThanOneRow() {
        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> executor.queryOptional(
                REPOSITORY,
                "FindUser",
                "SELECT id, name FROM users WHERE active = ? ORDER BY id",
                List.of(true),
                userMapper()
            )
        );

        assertEquals(
            "Query 'FindUser' in UsersRepository returned more than one row; expected at most one",
            exception.getMessage()
        );

        assertNull(exception.getCause());
    }

    @Test
    void shouldReturnAffectedRowCountForExecute() {
        int affected = executor.execute(
            REPOSITORY,
            "UpdateUserName",
            """
                UPDATE users
                SET name = ?
                WHERE id = ?
                """,
            List.of("Alicia", 1L)
        );

        assertEquals(1, affected);

        String name = executor.queryOne(
            REPOSITORY,
            "GetUserName",
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
                REPOSITORY,
                "DeleteUsers",
                "DELETE FROM missing_table",
                List.of()
            )
        );

        assertEquals(
            "Failed to execute query 'DeleteUsers' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());
    }

    @Test
    void shouldWrapSqlException() {
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT * FROM missing_table",
                List.of(),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals(
            "Failed to execute query 'GetUser' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());
    }

    @Test
    void shouldQueryThroughCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            User result = connectionExecutor.queryOne(
                REPOSITORY,
                "GetUser",
                """
                    SELECT id, name
                    FROM users
                    WHERE id = ?
                      AND active = ?
                    """,
                List.of(1L, true),
                userMapper()
            );

            assertEquals(new User(1L, "Alice"), result);

            assertTrue(
                connectionExecutor.queryOptional(
                    REPOSITORY,
                    "FindUser",
                    "SELECT id, name FROM users WHERE id = ?",
                    List.of(999L),
                    userMapper()
                ).isEmpty()
            );

            QueryCardinalityException exception = assertThrows(
                QueryCardinalityException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT id, name FROM users WHERE id = ?",
                    List.of(999L),
                    userMapper()
                )
            );

            assertEquals(
                "Query 'GetUser' in UsersRepository returned no row; expected exactly one",
                exception.getMessage()
            );

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldQueryManyThroughCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            List<User> results = connectionExecutor.queryMany(
                REPOSITORY,
                "ListUsers",
                """
                    SELECT id, name
                    FROM users
                    WHERE active = ?
                    ORDER BY id
                    """,
                List.of(true),
                userMapper()
            );

            assertEquals(
                List.of(
                    new User(1L, "Alice"),
                    new User(3L, "Charlie")
                ),
                results
            );

            assertTrue(
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    "SELECT id, name FROM users WHERE id = ?",
                    List.of(999L),
                    userMapper()
                ).isEmpty()
            );
        }
    }

    @Test
    void shouldReturnAffectedRowCountForExecuteThroughCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            int affected = connectionExecutor.execute(
                REPOSITORY,
                "RenameActiveUsers",
                """
                    UPDATE users
                    SET name = ?
                    WHERE active = ?
                    """,
                List.of("Renamed", true)
            );

            assertEquals(2, affected);

            assertEquals(
                "Renamed",
                connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUserName",
                    "SELECT name FROM users WHERE id = ?",
                    List.of(1L),
                    resultSet -> resultSet.getString("name")
                )
            );
        }
    }

    /**
     * Two operations run on the same caller-owned connection inside one
     * application-controlled transaction: the connection stays open, its
     * auto-commit setting and isolation level are unchanged, and the
     * application's rollback discards both operations.
     */
    @Test
    void shouldLeaveCallerOwnedConnectionOpenAndItsTransactionStateUnchanged() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            int isolation = connection.getTransactionIsolation();

            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            connectionExecutor.execute(
                REPOSITORY,
                "InsertUser",
                "INSERT INTO users (id, name, active) VALUES (?, ?, ?)",
                List.of(4L, "Dora", true)
            );

            connectionExecutor.execute(
                REPOSITORY,
                "UpdateUserName",
                "UPDATE users SET name = ? WHERE id = ?",
                List.of("Alicia", 1L)
            );

            assertEquals(
                List.of(
                    new User(1L, "Alicia"),
                    new User(4L, "Dora")
                ),
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    """
                        SELECT id, name
                        FROM users
                        WHERE id IN (?, ?)
                        ORDER BY id
                        """,
                    List.of(1L, 4L),
                    userMapper()
                )
            );

            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());
            assertEquals(isolation, connection.getTransactionIsolation());

            connection.rollback();

            assertEquals(
                List.of(new User(1L, "Alice")),
                connectionExecutor.queryMany(
                    REPOSITORY,
                    "ListUsers",
                    """
                        SELECT id, name
                        FROM users
                        WHERE id IN (?, ?)
                        ORDER BY id
                        """,
                    List.of(1L, 4L),
                    userMapper()
                )
            );
        }
    }

    @Test
    void shouldCloseStatementsAndResultSetsOfCallerOwnedConnection() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            connectionExecutor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id FROM users WHERE id = ?",
                List.of(1L),
                resultSet -> resultSet.getLong("id")
            );

            connectionExecutor.queryOptional(
                REPOSITORY,
                "FindUser",
                "SELECT id FROM users WHERE id = ?",
                List.of(1L),
                resultSet -> resultSet.getLong("id")
            );

            connectionExecutor.queryMany(
                REPOSITORY,
                "ListUsers",
                "SELECT id FROM users",
                List.of(),
                resultSet -> resultSet.getLong("id")
            );

            connectionExecutor.execute(
                REPOSITORY,
                "UpdateUserName",
                "UPDATE users SET name = ? WHERE id = ?",
                List.of("Alicia", 1L)
            );

            assertEquals(4, tracker.statements.size());
            assertEquals(3, tracker.resultSets.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldCloseStatementWhenExecutionFailsOnCallerOwnedConnection() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            QueryExecutionException exception = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT id FROM users WHERE id = ?",
                    List.of(),
                    resultSet -> resultSet.getLong("id")
                )
            );

            assertEquals(
                "Failed to execute query 'GetUser' in UsersRepository",
                exception.getMessage()
            );

            assertInstanceOf(SQLException.class, exception.getCause());

            assertEquals(1, tracker.statements.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
        }
    }

    /**
     * A cardinality failure is raised after the statement has executed, so the
     * statement and its result set are still closed and the caller-owned
     * connection is left open.
     */
    @Test
    void shouldCloseStatementWhenCardinalityFailsOnCallerOwnedConnection() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(
                tracker.track(Connection.class, connection)
            );

            QueryCardinalityException exception = assertThrows(
                QueryCardinalityException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT id FROM users WHERE active = ?",
                    List.of(true),
                    resultSet -> resultSet.getLong("id")
                )
            );

            assertEquals(
                "Query 'GetUser' in UsersRepository returned more than one row; expected exactly one",
                exception.getMessage()
            );

            assertEquals(1, tracker.statements.size());
            assertEquals(1, tracker.resultSets.size());
            tracker.assertAllStatementsAndResultSetsClosed();

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldWrapSqlExceptionForCallerOwnedConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcQueryExecutor connectionExecutor = new JdbcQueryExecutor(connection);

            QueryExecutionException queryException = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.queryOne(
                    REPOSITORY,
                    "GetUser",
                    "SELECT * FROM missing_table",
                    List.of(),
                    resultSet -> resultSet.getLong("id")
                )
            );

            assertEquals(
                "Failed to execute query 'GetUser' in UsersRepository",
                queryException.getMessage()
            );

            assertInstanceOf(SQLException.class, queryException.getCause());

            QueryExecutionException executeException = assertThrows(
                QueryExecutionException.class,
                () -> connectionExecutor.execute(
                    REPOSITORY,
                    "DeleteUsers",
                    "DELETE FROM missing_table",
                    List.of()
                )
            );

            assertEquals(
                "Failed to execute query 'DeleteUsers' in UsersRepository",
                executeException.getMessage()
            );

            assertInstanceOf(SQLException.class, executeException.getCause());

            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldCloseAcquiredConnectionForEachDataSourceOperation() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        dataSourceExecutor.queryOne(
            REPOSITORY,
            "GetUser",
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        dataSourceExecutor.queryOptional(
            REPOSITORY,
            "FindUser",
            "SELECT id FROM users WHERE id = ?",
            List.of(1L),
            resultSet -> resultSet.getLong("id")
        );

        dataSourceExecutor.queryMany(
            REPOSITORY,
            "ListUsers",
            "SELECT id FROM users",
            List.of(),
            resultSet -> resultSet.getLong("id")
        );

        dataSourceExecutor.execute(
            REPOSITORY,
            "UpdateUserName",
            "UPDATE users SET name = ? WHERE id = ?",
            List.of("Alicia", 1L)
        );

        assertEquals(4, tracker.connections.size());
        assertEquals(4, tracker.statements.size());
        assertEquals(3, tracker.resultSets.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    @Test
    void shouldCloseAcquiredConnectionWhenDataSourceOperationFails() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> dataSourceExecutor.queryOne(
                REPOSITORY,
                "GetUser",
                "SELECT id FROM users WHERE id = ?",
                List.of(),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals(
            "Failed to execute query 'GetUser' in UsersRepository",
            exception.getMessage()
        );

        assertInstanceOf(SQLException.class, exception.getCause());

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    /**
     * A cardinality failure on the DataSource path closes the acquired
     * connection together with the statement and its result set.
     */
    @Test
    void shouldCloseAcquiredConnectionWhenCardinalityFails() throws SQLException {
        JdbcResourceTracker tracker = new JdbcResourceTracker();

        JdbcQueryExecutor dataSourceExecutor = new JdbcQueryExecutor(
            tracker.track(DataSource.class, dataSource)
        );

        QueryCardinalityException exception = assertThrows(
            QueryCardinalityException.class,
            () -> dataSourceExecutor.queryOptional(
                REPOSITORY,
                "FindUser",
                "SELECT id FROM users WHERE active = ?",
                List.of(true),
                resultSet -> resultSet.getLong("id")
            )
        );

        assertEquals(
            "Query 'FindUser' in UsersRepository returned more than one row; expected at most one",
            exception.getMessage()
        );

        assertEquals(1, tracker.connections.size());
        assertEquals(1, tracker.statements.size());
        assertEquals(1, tracker.resultSets.size());

        tracker.assertAllConnectionsClosed();
        tracker.assertAllStatementsAndResultSetsClosed();
    }

    private RowMapper<User> userMapper() {
        return resultSet -> new User(
            resultSet.getLong("id"),
            resultSet.getString("name")
        );
    }

    private record User(
        long id,
        String name
    ) {
    }

    /**
     * Records the JDBC resources an executor opened, so a test can assert which
     * of them were closed. A tracked object is returned as a dynamic proxy that
     * delegates every call and tracks the connections, prepared statements, and
     * result sets it hands out.
     */
    private static final class JdbcResourceTracker {

        private final List<Connection> connections = new ArrayList<>();

        private final List<PreparedStatement> statements = new ArrayList<>();

        private final List<ResultSet> resultSets = new ArrayList<>();

        private <T> T track(Class<T> type, Object delegate) {
            return type.cast(
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { type },
                    (proxy, method, arguments) -> record(invoke(delegate, method, arguments))
                )
            );
        }

        private Object record(Object result) {
            if (result instanceof Connection connection) {
                connections.add(connection);

                return track(Connection.class, connection);
            }

            if (result instanceof PreparedStatement statement) {
                statements.add(statement);

                return track(PreparedStatement.class, statement);
            }

            if (result instanceof ResultSet resultSet) {
                resultSets.add(resultSet);

                return track(ResultSet.class, resultSet);
            }

            return result;
        }

        private Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void assertAllConnectionsClosed() throws SQLException {
            for (Connection connection : connections) {
                assertTrue(connection.isClosed());
            }
        }

        private void assertAllStatementsAndResultSetsClosed() throws SQLException {
            for (PreparedStatement statement : statements) {
                assertTrue(statement.isClosed());
            }

            for (ResultSet resultSet : resultSets) {
                assertTrue(resultSet.isClosed());
            }
        }
    }
}
