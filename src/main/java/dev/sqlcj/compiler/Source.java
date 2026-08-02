package dev.sqlcj.compiler;

import dev.sqlcj.parser.Query;

import java.util.List;

public record Source(
        String schema,
        List<Query> queries
) {
}
