package org.sqlcj.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the transaction helper over H2: the committed and rolled-back paths,
 * the failure of every JDBC call the helper itself makes, and the auto-commit
 * and closing rules it follows on each of those paths.
 */
class JdbcTransactionsTest {

    /** The generated repository the operations of these tests report. */
    private static final String REPOSITORY = "UsersRepository";

    private static final String INSERT_USER = "INSERT INTO users (id, name) VALUES (?, ?)";

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {

            statement.execute("""
                CREATE TABLE users (
                    id   BIGINT PRIMARY KEY,
                    name VARCHAR(255)
                )
                """);
        }
    }

    /**
     * A normal return commits the work of the callback, including a generated
     * operation and a hand-written statement on the same connection, and
     * {@code inTransaction} returns the callback's value. Auto-commit is
     * disabled before the callback runs, restored after the commit, and the
     * connection is closed.
     */
    @Test
    void shouldReturnTheCallbackValueAndCommit() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        String name = transactions.inTransaction(connection -> {
            assertFalse(connection.getAutoCommit());

            new JdbcQueryExecutor(connection).execute(
                REPOSITORY,
                "InsertUser",
                INSERT_USER,
                List.of(1L, "Alice")
            );

            insert(connection, 2L, "Bob");

            return "committed";
        });

        assertEquals("committed", name);
        assertEquals(List.of("Alice", "Bob"), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "commit", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /** A callback that returns no value commits the same way. */
    @Test
    void shouldCommitACallbackThatReturnsNoValue() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        transactions.useTransaction(connection -> insert(connection, 1L, "Alice"));

        assertEquals(List.of("Alice"), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "commit", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A runtime exception the callback throws rolls the transaction back and is
     * rethrown as the same instance, after which auto-commit is restored and
     * the connection is closed.
     */
    @Test
    void shouldRollBackAndRethrowARuntimeException() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        IllegalStateException thrown = new IllegalStateException("callback failed");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> transactions.useTransaction(connection -> {
                insert(connection, 1L, "Alice");

                throw thrown;
            })
        );

        assertSame(thrown, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(List.of(), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /** An error the callback throws rolls the transaction back the same way. */
    @Test
    void shouldRollBackAndRethrowAnError() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        CallbackError thrown = new CallbackError();

        CallbackError failure = assertThrows(
            CallbackError.class,
            () -> transactions.useTransaction(connection -> {
                insert(connection, 1L, "Alice");

                throw thrown;
            })
        );

        assertSame(thrown, failure);
        assertEquals(List.of(), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A checked {@link SQLException} raised by hand-written JDBC in the
     * callback rolls the transaction back and is rethrown as the same instance,
     * without the caller catching anything the helper added.
     */
    @Test
    void shouldRollBackAndRethrowACheckedSqlException() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        AtomicReference<SQLException> raised = new AtomicReference<>();

        SQLException failure = assertThrows(
            SQLException.class,
            () -> transactions.useTransaction(connection -> {
                insert(connection, 1L, "Alice");

                try (
                    PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO missing_table (id) VALUES (?)"
                    )
                ) {
                    statement.setLong(1, 2L);
                    statement.executeUpdate();
                } catch (SQLException e) {
                    raised.set(e);

                    throw e;
                }
            })
        );

        assertSame(raised.get(), failure);
        assertEquals(List.of(), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A {@link QueryCardinalityException} a generated operation raises on the
     * callback's connection rolls the transaction back, so the write the same
     * transaction already made is discarded, and is rethrown as the same
     * instance.
     */
    @Test
    void shouldRollBackAndRethrowACardinalityFailure() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        AtomicReference<QueryCardinalityException> raised = new AtomicReference<>();

        QueryCardinalityException failure = assertThrows(
            QueryCardinalityException.class,
            () -> transactions.useTransaction(connection -> {
                QueryExecutor executor = new JdbcQueryExecutor(connection);

                executor.execute(REPOSITORY, "InsertUser", INSERT_USER, List.of(1L, "Alice"));

                try {
                    executor.queryOne(
                        REPOSITORY,
                        "GetUser",
                        "SELECT id FROM users WHERE id = ?",
                        List.of(999L),
                        resultSet -> resultSet.getLong("id")
                    );
                } catch (QueryCardinalityException e) {
                    raised.set(e);

                    throw e;
                }
            })
        );

        assertSame(raised.get(), failure);

        assertEquals(
            "Query 'GetUser' in UsersRepository returned no row; expected exactly one",
            failure.getMessage()
        );

        assertEquals(List.of(), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A connection that cannot be obtained fails the transaction before the
     * callback runs, and no connection is left to release.
     */
    @Test
    void shouldFailWhenTheConnectionCannotBeObtained() {
        SQLException cause = new SQLException("no connection");

        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .failing("getConnection", cause);

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        AtomicBoolean ran = new AtomicBoolean();

        TransactionException failure = assertThrows(
            TransactionException.class,
            () -> transactions.useTransaction(connection -> ran.set(true))
        );

        assertEquals("Failed to begin a transaction", failure.getMessage());
        assertSame(cause, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);

        assertFalse(ran.get());
        assertEquals(List.of("getConnection"), recorder.calls);
        assertTrue(recorder.connections.isEmpty());
    }

    /**
     * A connection whose auto-commit cannot be disabled fails the transaction
     * before the callback runs, and that connection is still closed.
     */
    @Test
    void shouldFailWhenAutoCommitCannotBeDisabled() throws SQLException {
        SQLException cause = new SQLException("cannot disable auto-commit");

        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .failing("setAutoCommit(false)", cause);

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        AtomicBoolean ran = new AtomicBoolean();

        TransactionException failure = assertThrows(
            TransactionException.class,
            () -> transactions.useTransaction(connection -> ran.set(true))
        );

        assertEquals("Failed to begin a transaction", failure.getMessage());
        assertSame(cause, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);

        assertFalse(ran.get());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A commit that fails raises a {@link TransactionException}, leaves
     * auto-commit untouched so that the restore cannot commit the open
     * transaction, and still closes the connection, which discards the work.
     */
    @Test
    void shouldFailWhenTheCommitFails() throws SQLException {
        SQLException cause = new SQLException("cannot commit");

        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .failing("commit", cause);

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        TransactionException failure = assertThrows(
            TransactionException.class,
            () -> transactions.useTransaction(connection -> insert(connection, 1L, "Alice"))
        );

        assertEquals("Failed to commit the transaction", failure.getMessage());
        assertSame(cause, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);

        assertEquals(List.of(), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "commit", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A rollback that fails is attached to the callback's failure as a
     * suppressed {@link TransactionException}, and auto-commit is left
     * untouched so that the restore cannot commit the work the rollback failed
     * to discard.
     */
    @Test
    void shouldAttachARollbackFailureToTheCallbackFailure() throws SQLException {
        SQLException cause = new SQLException("cannot roll back");

        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .failing("rollback", cause);

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        IllegalStateException thrown = new IllegalStateException("callback failed");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> transactions.useTransaction(connection -> {
                insert(connection, 1L, "Alice");

                throw thrown;
            })
        );

        assertSame(thrown, failure);
        assertEquals(1, failure.getSuppressed().length);

        TransactionException suppressed = assertInstanceOf(
            TransactionException.class,
            failure.getSuppressed()[0]
        );

        assertEquals("Failed to roll back the transaction", suppressed.getMessage());
        assertSame(cause, suppressed.getCause());

        assertEquals(List.of(), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "rollback", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * Auto-commit that cannot be restored after a successful commit raises a
     * {@link TransactionException} naming the release, and the connection is
     * closed all the same.
     */
    @Test
    void shouldFailWhenAutoCommitCannotBeRestored() throws SQLException {
        SQLException cause = new SQLException("cannot restore auto-commit");

        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .failing("setAutoCommit(true)", cause);

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        TransactionException failure = assertThrows(
            TransactionException.class,
            () -> transactions.useTransaction(connection -> insert(connection, 1L, "Alice"))
        );

        assertEquals("Failed to release the transaction's connection", failure.getMessage());
        assertSame(cause, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);

        assertEquals(List.of("Alice"), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "commit", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A connection that cannot be closed raises a {@link TransactionException}
     * naming the release when the transaction itself succeeded, and is attached
     * to a pending failure as a suppressed exception otherwise.
     */
    @Test
    void shouldReportAFailureToCloseTheConnection() throws SQLException {
        SQLException closeCause = new SQLException("cannot close");

        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .failing("close", closeCause);

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        TransactionException failure = assertThrows(
            TransactionException.class,
            () -> transactions.useTransaction(connection -> insert(connection, 1L, "Alice"))
        );

        assertEquals("Failed to release the transaction's connection", failure.getMessage());
        assertSame(closeCause, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(List.of("Alice"), names());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "commit", "setAutoCommit(true)", "close"),
            recorder.calls
        );

        SQLException commitCause = new SQLException("cannot commit");

        TransactionRecorder pendingRecorder = new TransactionRecorder(dataSource)
            .failing("commit", commitCause)
            .failing("close", closeCause);

        JdbcTransactions pendingTransactions = new JdbcTransactions(pendingRecorder.dataSource());

        TransactionException pendingFailure = assertThrows(
            TransactionException.class,
            () -> pendingTransactions.useTransaction(connection -> insert(connection, 2L, "Bob"))
        );

        assertEquals("Failed to commit the transaction", pendingFailure.getMessage());
        assertSame(commitCause, pendingFailure.getCause());
        assertEquals(1, pendingFailure.getSuppressed().length);

        TransactionException suppressed = assertInstanceOf(
            TransactionException.class,
            pendingFailure.getSuppressed()[0]
        );

        assertEquals("Failed to release the transaction's connection", suppressed.getMessage());
        assertSame(closeCause, suppressed.getCause());

        assertEquals(
            List.of("getConnection", "setAutoCommit(false)", "commit", "close"),
            pendingRecorder.calls
        );

        recorder.closeDelegates();
        pendingRecorder.closeDelegates();
    }

    /**
     * A connection the DataSource hands out with auto-commit already disabled
     * is not reconfigured at all: the helper neither disables nor restores
     * auto-commit, and only commits and closes.
     */
    @Test
    void shouldNotTouchAutoCommitOfAConnectionThatAlreadyHasItDisabled() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource)
            .handingOutConnectionsWithoutAutoCommit();

        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        transactions.useTransaction(connection -> insert(connection, 1L, "Alice"));

        assertEquals(List.of("Alice"), names());

        assertEquals(
            List.of("getConnection", "commit", "close"),
            recorder.calls
        );

        recorder.assertOneClosedConnection();
    }

    /**
     * A call made inside another call's callback runs on its own connection as
     * an independent transaction: its commit stands even though the outer
     * transaction is rolled back afterwards.
     */
    @Test
    void shouldRunANestedCallAsAnIndependentTransaction() throws SQLException {
        TransactionRecorder recorder = new TransactionRecorder(dataSource);
        JdbcTransactions transactions = new JdbcTransactions(recorder.dataSource());

        IllegalStateException thrown = new IllegalStateException("outer failed");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> transactions.useTransaction(outer -> {
                insert(outer, 1L, "Alice");

                transactions.useTransaction(inner -> {
                    assertNotSame(outer, inner);

                    insert(inner, 2L, "Bob");
                });

                throw thrown;
            })
        );

        assertSame(thrown, failure);
        assertEquals(List.of("Bob"), names());

        assertEquals(
            List.of(
                "getConnection",
                "setAutoCommit(false)",
                "getConnection",
                "setAutoCommit(false)",
                "commit",
                "setAutoCommit(true)",
                "close",
                "rollback",
                "setAutoCommit(true)",
                "close"
            ),
            recorder.calls
        );

        assertEquals(2, recorder.connections.size());
        recorder.assertAllConnectionsClosed();
    }

    /**
     * A callback that throws no checked exception needs neither a
     * {@code catch} nor a {@code throws} clause, so this test method declares
     * neither.
     */
    @Test
    void shouldRequireNoCatchOrThrowsForACallbackThatThrowsNothing() {
        JdbcTransactions transactions = new JdbcTransactions(dataSource);

        long rows = transactions.inTransaction(connection -> 1L);

        assertEquals(1L, rows);

        transactions.useTransaction(connection -> assertNotNull(connection));
    }

    private void insert(Connection connection, long id, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_USER)) {
            statement.setLong(1, id);
            statement.setString(2, name);

            assertEquals(1, statement.executeUpdate());
        }
    }

    /** The committed names, read on a connection of its own. */
    private List<String> names() throws SQLException {
        List<String> names = new ArrayList<>();

        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT name FROM users ORDER BY id")
        ) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        }

        return names;
    }

    /** An error a callback of these tests throws, distinct from an assertion failure. */
    private static final class CallbackError extends Error {
    }

    /**
     * Records the transaction calls the helper made and can fail any one of
     * them. A tracked DataSource and connection are dynamic proxies that
     * delegate every other call; a call configured to fail is recorded and then
     * raises its {@link SQLException} instead of reaching the delegate, so the
     * delegate connection of a failing {@code close} is released by
     * {@link #closeDelegates()} instead.
     */
    private static final class TransactionRecorder {

        /** The connection calls that are recorded and that can be made to fail. */
        private static final List<String> RECORDED = List.of(
            "setAutoCommit",
            "commit",
            "rollback",
            "close"
        );

        private final DataSource delegate;

        private final List<String> calls = new ArrayList<>();

        private final List<Connection> connections = new ArrayList<>();

        private final Map<String, SQLException> failures = new LinkedHashMap<>();

        private boolean autoCommitAlreadyDisabled;

        private TransactionRecorder(DataSource delegate) {
            this.delegate = delegate;
        }

        /** Fails the named recorded call with the given {@link SQLException}. */
        private TransactionRecorder failing(String call, SQLException failure) {
            failures.put(call, failure);

            return this;
        }

        /**
         * Hands out connections whose auto-commit is already disabled, without
         * recording that preparation as a call of the helper.
         */
        private TransactionRecorder handingOutConnectionsWithoutAutoCommit() {
            autoCommitAlreadyDisabled = true;

            return this;
        }

        private DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { DataSource.class },
                (proxy, method, arguments) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return invoke(delegate, method, arguments);
                    }

                    record("getConnection");

                    Connection connection = (Connection) invoke(delegate, method, arguments);

                    connections.add(connection);

                    if (autoCommitAlreadyDisabled) {
                        connection.setAutoCommit(false);
                    }

                    return track(connection);
                }
            );
        }

        private Connection track(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, arguments) -> {
                    if (RECORDED.contains(method.getName())) {
                        record(call(method, arguments));
                    }

                    return invoke(connection, method, arguments);
                }
            );
        }

        /** The recorded name of a call, with the argument of {@code setAutoCommit}. */
        private String call(Method method, Object[] arguments) {
            if ("setAutoCommit".equals(method.getName())) {
                return "setAutoCommit(%s)".formatted(arguments[0]);
            }

            return method.getName();
        }

        private void record(String call) throws SQLException {
            calls.add(call);

            SQLException failure = failures.get(call);

            if (failure != null) {
                throw failure;
            }
        }

        private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void assertOneClosedConnection() throws SQLException {
            assertEquals(1, connections.size());

            assertAllConnectionsClosed();
        }

        private void assertAllConnectionsClosed() throws SQLException {
            for (Connection connection : connections) {
                assertTrue(connection.isClosed());
            }
        }

        /** Closes the delegate connections a failing {@code close} left open. */
        private void closeDelegates() throws SQLException {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }
}
