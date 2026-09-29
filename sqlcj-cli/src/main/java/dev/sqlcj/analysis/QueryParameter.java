package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

/**
 * One analyzed parameter occurrence.
 *
 * @param enumType the schema's declared name of the parameter's enum type when
 *                 {@code type} is {@link ColumnType#ENUM}, and {@code null}
 *                 otherwise
 * @param array    whether the parameter is a one-dimensional array of
 *                 {@code type}, whose Java type is a list of the element's Java
 *                 type
 * @param blankPadded whether the parameter's column is declared with the
 *                    blank-padded {@code CHAR} or {@code CHARACTER} spelling of
 *                    {@link ColumnType#VARCHAR}, which PostgreSQL names
 *                    {@code bpchar}
 */
public record QueryParameter(
    int index,
    String name,
    ColumnType type,
    String enumType,
    boolean array,
    boolean blankPadded
) {

    /** A parameter of a type whose Java type is decided by {@code type} alone. */
    public QueryParameter(int index, String name, ColumnType type) {
        this(index, name, type, null);
    }

    /** A scalar parameter, which may be of an enum type. */
    public QueryParameter(int index, String name, ColumnType type, String enumType) {
        this(index, name, type, enumType, false);
    }

    /** A parameter whose declared spelling is not blank padded. */
    public QueryParameter(
        int index,
        String name,
        ColumnType type,
        String enumType,
        boolean array
    ) {
        this(index, name, type, enumType, array, false);
    }
}
