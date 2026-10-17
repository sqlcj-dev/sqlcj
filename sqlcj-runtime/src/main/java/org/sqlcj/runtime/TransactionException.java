package org.sqlcj.runtime;

/**
 * Thrown when {@link JdbcTransactions} itself fails to manage a transaction:
 * it could not begin one, could not commit it, could not roll it back, or could
 * not release its connection. The {@code SQLException} the JDBC driver raised is
 * always the cause.
 *
 * <p>A failure of the callback's own work is never reported this way. A
 * callback's exception or error is rethrown unchanged, and a failing generated
 * operation still raises {@link QueryExecutionException}, which names the query.
 *
 * <p>A rollback failure and a connection-release failure that happen while
 * another failure is already pending are attached to that failure as a
 * suppressed {@code TransactionException} rather than thrown.
 */
public final class TransactionException extends RuntimeException {

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
