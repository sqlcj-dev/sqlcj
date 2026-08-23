package dev.sqlcj.analysis;

import dev.sqlcj.parser.QueryType;

import java.util.List;

public record QueryModel(
        String name,
        QueryType type,
        String table,
        String sql,
        List<QueryColumn> columns,
        List<QueryParameter> parameters
) {
}
