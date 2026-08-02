package dev.sqlcj.parser;

public record Query(
        String name,
        QueryType type,
        String sql
) {
}
