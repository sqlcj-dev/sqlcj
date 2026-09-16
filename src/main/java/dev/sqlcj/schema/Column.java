package dev.sqlcj.schema;

public record Column(
    String name,
    ColumnType type,
    boolean nullable
) {
}
