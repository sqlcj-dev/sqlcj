package org.sqlcj.runtime;

import java.sql.Connection;

/**
 * A unit of work {@link JdbcTransactions#inTransaction} runs on one connection
 * and that returns a value.
 *
 * <p>The callback receives the connection of the transaction it runs in, with
 * auto-commit disabled. It binds the generated repositories it needs over
 * {@code new JdbcQueryExecutor(connection)} and may run hand-written JDBC code
 * on the same connection, so every operation takes part in the same
 * transaction. It never commits, rolls back, closes, or reconfigures the
 * connection itself; {@link JdbcTransactions} does that around it.
 *
 * <p>A normal return commits the transaction and the returned value becomes the
 * result of {@link JdbcTransactions#inTransaction}. Any exception or error
 * rolls the transaction back and is rethrown unchanged.
 *
 * @param <T> the type of the value the unit of work returns
 * @param <X> the checked exception the unit of work may throw, inferred as
 *            {@link RuntimeException} when it throws none
 */
@FunctionalInterface
public interface TransactionCallback<T, X extends Exception> {

    T run(Connection connection) throws X;
}
