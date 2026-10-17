package org.sqlcj.runtime;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs a unit of work on one connection inside one transaction.
 *
 * <p>A call obtains a connection from the {@link DataSource}, disables
 * auto-commit on it, and hands it to the callback. The callback binds the
 * generated repositories it needs over {@code new JdbcQueryExecutor(connection)}
 * and may run hand-written JDBC code on the same connection, so every operation
 * takes part in the same transaction:
 *
 * <pre>{@code
 * JdbcTransactions transactions = new JdbcTransactions(dataSource);
 *
 * Long id = transactions.inTransaction(connection -> {
 *     AuthorRepository authors = new AuthorRepository(new JdbcQueryExecutor(connection));
 *
 *     Author author = authors.createAuthor("Jane Austen", "English novelist");
 *
 *     authors.recordAudit(author.id(), "created");
 *
 *     return author.id();
 * });
 * }</pre>
 *
 * <p>Each call manages the transaction as follows:
 *
 * <ul>
 * <li>A normal return commits the transaction, and
 * {@link #inTransaction} returns the callback's value.</li>
 * <li>Any exception or error the callback throws rolls the transaction back and
 * is rethrown unchanged, as the same instance.</li>
 * <li>A failure to begin or to commit raises
 * {@link TransactionException} carrying the {@code SQLException} as its
 * cause; on a begin failure the callback does not run.</li>
 * <li>A failure to roll back is attached to the callback's failure as a
 * suppressed {@link TransactionException}.</li>
 * <li>Auto-commit is restored to {@code true} only when this helper disabled it,
 * and only after a successful commit or rollback, because changing
 * auto-commit during an open transaction commits it.</li>
 * <li>The connection is closed on every path. A failure to restore auto-commit
 * or to close raises {@link TransactionException} when no other failure is
 * pending, and is attached to the pending failure as a suppressed
 * {@link TransactionException} otherwise.</li>
 * </ul>
 *
 * <p>Each call uses its own connection, so a call made inside another call's
 * callback is an independent transaction: it commits or rolls back on its own,
 * and the outer transaction's outcome does not change it.
 *
 * <p>This helper covers one transaction over one connection. Anything else a
 * transaction may need, such as an isolation level, a savepoint, or a
 * {@code SET LOCAL} setting, is applied by the application on a connection it
 * owns itself, with repositories bound over
 * {@link JdbcQueryExecutor#JdbcQueryExecutor(Connection)}.
 *
 * <p>This helper is blocking and synchronous, and its connection is confined to
 * the thread that runs the call.
 */
public final class JdbcTransactions {

    private static final String BEGIN_FAILURE = "Failed to begin a transaction";

    private static final String COMMIT_FAILURE = "Failed to commit the transaction";

    private static final String ROLLBACK_FAILURE = "Failed to roll back the transaction";

    private static final String RELEASE_FAILURE = "Failed to release the transaction's connection";

    private final DataSource dataSource;

    /**
     * Creates a helper that obtains one connection from the given
     * {@code dataSource} per transaction and closes it before the transaction
     * returns.
     */
    public JdbcTransactions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs the given {@code callback} on one connection inside one transaction
     * and returns its value, committing on a normal return and rolling back on
     * any failure.
     */
    public <T, X extends Exception> T inTransaction(TransactionCallback<T, X> callback) throws X {
        Transaction transaction = begin();

        T result;

        try {
            result = callback.run(transaction.connection());
        } catch (Throwable failure) {
            rollBack(transaction, failure);

            throw rethrow(failure);
        }

        commit(transaction);

        return result;
    }

    /**
     * Runs the given {@code callback} on one connection inside one transaction,
     * committing on a normal return and rolling back on any failure.
     */
    public <X extends Exception> void useTransaction(TransactionConsumer<X> callback) throws X {
        this.<Void, X>inTransaction(connection -> {
            callback.accept(connection);

            return null;
        });
    }

    /**
     * Obtains a connection and disables its auto-commit, remembering whether
     * this call disabled it. A failure closes a connection already obtained and
     * raises a {@link TransactionException}, so the callback never runs.
     */
    private Transaction begin() {
        Connection connection;

        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new TransactionException(BEGIN_FAILURE, e);
        }

        try {
            if (connection.getAutoCommit()) {
                connection.setAutoCommit(false);

                return new Transaction(connection, true);
            }

            return new Transaction(connection, false);
        } catch (SQLException e) {
            TransactionException failure = new TransactionException(BEGIN_FAILURE, e);

            attach(failure, release(connection, false));

            throw failure;
        }
    }

    /**
     * Commits the transaction and releases its connection. A commit failure
     * raises a {@link TransactionException} and leaves auto-commit untouched,
     * so a transaction the commit left open is not committed by the restore.
     */
    private void commit(Transaction transaction) {
        TransactionException commitFailure = null;

        try {
            transaction.connection().commit();
        } catch (SQLException e) {
            commitFailure = new TransactionException(COMMIT_FAILURE, e);
        }

        TransactionException releaseFailure = release(
            transaction.connection(),
            transaction.autoCommitDisabled() && commitFailure == null
        );

        if (commitFailure != null) {
            attach(commitFailure, releaseFailure);

            throw commitFailure;
        }

        if (releaseFailure != null) {
            throw releaseFailure;
        }
    }

    /**
     * Rolls the transaction back and releases its connection, attaching a
     * rollback failure and a release failure to the pending {@code failure} as
     * suppressed exceptions. A failed rollback leaves auto-commit untouched, so
     * the restore cannot commit the work the rollback failed to discard.
     */
    private void rollBack(Transaction transaction, Throwable failure) {
        boolean rolledBack = true;

        try {
            transaction.connection().rollback();
        } catch (SQLException e) {
            rolledBack = false;

            failure.addSuppressed(new TransactionException(ROLLBACK_FAILURE, e));
        }

        attach(
            failure,
            release(
                transaction.connection(),
                transaction.autoCommitDisabled() && rolledBack
            )
        );
    }

    /**
     * Restores auto-commit when asked and always closes the connection,
     * returning the first failure of either step, with a later one attached to
     * it as suppressed, or {@code null} when both succeeded.
     */
    private TransactionException release(Connection connection, boolean restoreAutoCommit) {
        TransactionException failure = null;

        if (restoreAutoCommit) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                failure = new TransactionException(RELEASE_FAILURE, e);
            }
        }

        try {
            connection.close();
        } catch (SQLException e) {
            TransactionException closeFailure = new TransactionException(RELEASE_FAILURE, e);

            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }

        return failure;
    }

    private static void attach(Throwable failure, TransactionException suppressed) {
        if (suppressed != null) {
            failure.addSuppressed(suppressed);
        }
    }

    /**
     * Rethrows the given failure as the same instance, unchanged. The cast is
     * erased, so a checked exception the callback declared is rethrown without
     * the compiler requiring it again here; the caller of
     * {@link #inTransaction} still sees it as the callback's own {@code X}.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E {
        throw (E) failure;
    }

    /** One connection and whether this helper disabled its auto-commit. */
    private record Transaction(
        Connection connection,
        boolean autoCommitDisabled
    ) {
    }
}
