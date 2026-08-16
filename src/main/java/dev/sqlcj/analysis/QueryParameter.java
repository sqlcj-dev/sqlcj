package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

public record QueryParameter(
        int index,
        String name,
        ColumnType type
) {
}
