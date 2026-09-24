package dev.sqlcj.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Executes the SQL of one generated repository method.
 *
 * <p>Every operation is given the generated repository's class name and the
 * name of the query it was generated from, so an execution or cardinality
 * failure names the query the application called.
 */
public interface QueryExecutor {

    /**
     * Executes a query that must return exactly one row and returns that row.
     *
     * @throws QueryCardinalityException if no row or more than one row is
     *                                   returned
     */
    <T> T queryOne(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    );

    /**
     * Executes a query that may return at most one row and returns that row if
     * there is one, or an empty {@link Optional} if there is none.
     *
     * @throws QueryCardinalityException if more than one row is returned
     */
    <T> Optional<T> queryOptional(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    );

    /**
     * Executes a query and returns every row it produced, in the order the
     * database produced it, or an empty list if it produced none.
     */
    <T> List<T> queryMany(
        String repository,
        String query,
        String sql,
        List<?> parameters,
        RowMapper<T> mapper
    );

    /**
     * Executes a write statement and returns its affected-row count.
     */
    int execute(String repository, String query, String sql, List<?> parameters);
}
