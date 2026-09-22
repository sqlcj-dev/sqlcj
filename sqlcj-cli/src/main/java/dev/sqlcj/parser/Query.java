package dev.sqlcj.parser;

/**
 * One named query.
 *
 * @param line the one-based line of the query header in its source, or
 *             {@code 0} when the source line is unknown
 */
public record Query(
    String name,
    QueryType type,
    String sql,
    int line
) {

    public Query(String name, QueryType type, String sql) {
        this(name, type, sql, 0);
    }
}
