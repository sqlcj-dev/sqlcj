package dev.sqlcj.runtime;

/**
 * Thrown when a query returned a number of rows its annotation does not allow:
 * a {@code :one} query that returned no row or more than one row, or a
 * {@code :optional} query that returned more than one row.
 *
 * <p>The message names the query and the generated repository it belongs to.
 * The statement itself executed successfully, so a returning write that fails
 * this check has already changed the database.
 */
public final class QueryCardinalityException extends QueryExecutionException {

    public QueryCardinalityException(String message) {
        super(message, null);
    }
}
