package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

/**
 * One analyzed result column.
 *
 * @param enumType the schema's declared name of the column's enum type when
 *                 {@code type} is {@link ColumnType#ENUM}, and {@code null}
 *                 otherwise
 */
public record QueryColumn(
    String name,
    ColumnType type,
    boolean nullable,
    String enumType
) {

    /** A column of a type whose Java type is decided by {@code type} alone. */
    public QueryColumn(String name, ColumnType type, boolean nullable) {
        this(name, type, nullable, null);
    }
}
