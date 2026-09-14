package dev.sqlcj.analysis;

import dev.sqlcj.parser.QueryType;

import java.util.List;

/**
 * Analyzed query facts required by code generation.
 *
 * @param executableSql            JDBC-executable SQL where supported {@code $N}
 *                                 placeholders are replaced by {@code ?}
 * @param bindingParameterIndexes  placeholder indexes in the textual order of the
 *                                 {@code ?} positions in {@link #executableSql()}
 * @param parameters               query parameters in logical placeholder-index order
 */
public record QueryModel(
        String name,
        QueryType type,
        String table,
        String executableSql,
        List<Integer> bindingParameterIndexes,
        List<QueryColumn> columns,
        List<QueryParameter> parameters
) {
}
