package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

public record QueryParameter(
        int index,
        ColumnType type
) {
}
