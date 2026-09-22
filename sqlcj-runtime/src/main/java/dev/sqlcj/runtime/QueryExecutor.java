package dev.sqlcj.runtime;

import java.util.List;

public interface QueryExecutor {

    <T> T query(String sql, List<?> parameters, RowMapper<T> mapper);

    <T> List<T> queryMany(String sql, List<?> parameters, RowMapper<T> mapper);

    /**
     * Executes a write statement and returns its affected-row count.
     */
    int execute(String sql, List<?> parameters);
}
