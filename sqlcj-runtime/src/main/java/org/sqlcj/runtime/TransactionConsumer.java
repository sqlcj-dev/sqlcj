package org.sqlcj.runtime;

import java.sql.Connection;

/**
 * A unit of work {@link JdbcTransactions#useTransaction} runs on one connection
 * and that returns no value.
 *
 * <p>The callback receives the connection of the transaction it runs in, with
 * auto-commit disabled, and follows the same rules as
 * {@link TransactionCallback}: it binds the generated repositories it needs
 * over {@code new JdbcQueryExecutor(connection)}, may run hand-written JDBC
 * code on the same connection, and never commits, rolls back, closes, or
 * reconfigures the connection itself.
 *
 * <p>A normal return commits the transaction. Any exception or error rolls the
 * transaction back and is rethrown unchanged.
 *
 * @param <X> the checked exception the unit of work may throw, inferred as
 *            {@link RuntimeException} when it throws none
 */
@FunctionalInterface
public interface TransactionConsumer<X extends Exception> {

    void accept(Connection connection) throws X;
}
