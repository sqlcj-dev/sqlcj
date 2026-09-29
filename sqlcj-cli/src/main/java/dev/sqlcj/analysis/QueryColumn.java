package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

/**
 * One analyzed result column.
 *
 * @param enumType the schema's declared name of the column's enum type when
 *                 {@code type} is {@link ColumnType#ENUM}, and {@code null}
 *                 otherwise
 * @param array    whether the column is a one-dimensional array of {@code type},
 *                 whose Java type is a list of the element's Java type
 */
public record QueryColumn(
    String name,
    ColumnType type,
    boolean nullable,
    String enumType,
    boolean array
) {

    /** A column of a type whose Java type is decided by {@code type} alone. */
    public QueryColumn(String name, ColumnType type, boolean nullable) {
        this(name, type, nullable, null);
    }

    /** A scalar column, which may be of an enum type. */
    public QueryColumn(String name, ColumnType type, boolean nullable, String enumType) {
        this(name, type, nullable, enumType, false);
    }
}
