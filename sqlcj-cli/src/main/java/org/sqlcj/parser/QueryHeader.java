package org.sqlcj.parser;

public record QueryHeader(
    String name,
    QueryType type
) {
}
