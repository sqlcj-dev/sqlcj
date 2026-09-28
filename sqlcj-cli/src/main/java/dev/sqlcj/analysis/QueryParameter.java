package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

/**
 * One analyzed parameter occurrence.
 *
 * @param enumType the schema's declared name of the parameter's enum type when
 *                 {@code type} is {@link ColumnType#ENUM}, and {@code null}
 *                 otherwise
 */
public record QueryParameter(
    int index,
    String name,
    ColumnType type,
    String enumType
) {

    /** A parameter of a type whose Java type is decided by {@code type} alone. */
    public QueryParameter(int index, String name, ColumnType type) {
        this(index, name, type, null);
    }
}
