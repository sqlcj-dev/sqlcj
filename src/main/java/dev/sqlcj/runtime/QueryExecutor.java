package dev.sqlcj.runtime;

import java.util.List;

public interface QueryExecutor {

    <T> T query(
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
    );

    <T> List<T> queryMany(
            String sql,
            List<?> parameters,
            RowMapper<T> mapper
    );
}
