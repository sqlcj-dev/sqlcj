package dev.sqlcj.analysis;

import dev.sqlcj.parser.QueryType;

import java.util.List;

/**
 * Analyzed query facts required by code generation.
 *
 * @param executableSql            JDBC-executable SQL where every parsed
 *                                 {@code $N} and {@code :name} parameter token
 *                                 is replaced by {@code ?}
 * @param bindingParameterIndexes  the logical parameter number of each
 *                                 {@code ?} position in
 *                                 {@link #executableSql()}, in textual order
 * @param parameters               one query parameter per logical parameter
 *                                 number, in that order
 * @param rowTable                 the schema's declared name of the table whose
 *                                 complete row this query returns, or
 *                                 {@code null} when the result is specific to
 *                                 this query
 */
public record QueryModel(
    String name,
    QueryType type,
    String table,
    String executableSql,
    List<Integer> bindingParameterIndexes,
    List<QueryColumn> columns,
    List<QueryParameter> parameters,
    String rowTable
) {
}
