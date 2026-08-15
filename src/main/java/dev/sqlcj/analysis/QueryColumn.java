package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

public record QueryColumn(
        String name,
        ColumnType type,
        boolean nullable
) {
}
