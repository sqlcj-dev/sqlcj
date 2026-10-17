package dev.sqlcj.analysis;

import dev.sqlcj.schema.ColumnType;

import java.util.List;

/**
 * One analyzed result column, which is either a plain column read from the
 * result or the complete row of one table embedded as one component.
 *
 * @param enumType   the schema's declared name of the column's enum type when
 *                   {@code type} is {@link ColumnType#ENUM}, and {@code null}
 *                   otherwise
 * @param array      whether the column is a one-dimensional array of
 *                   {@code type}, whose Java type is a list of the element's
 *                   Java type
 * @param rowTable   the schema's declared name of the table whose complete row
 *                   this component holds, and {@code null} for a plain column,
 *                   which carries a type of its own instead
 * @param rowColumns the embedded row's own columns, in schema order, each with
 *                   the nullability the schema declares for it, and empty for a
 *                   plain column
 */
public record QueryColumn(
    String name,
    ColumnType type,
    boolean nullable,
    String enumType,
    boolean array,
    String rowTable,
    List<QueryColumn> rowColumns
) {

    /** A column of a type whose Java type is decided by {@code type} alone. */
    public QueryColumn(String name, ColumnType type, boolean nullable) {
        this(name, type, nullable, null);
    }

    /** A scalar column, which may be of an enum type. */
    public QueryColumn(String name, ColumnType type, boolean nullable, String enumType) {
        this(name, type, nullable, enumType, false);
    }

    /** A plain column, which holds no embedded row. */
    public QueryColumn(
        String name,
        ColumnType type,
        boolean nullable,
        String enumType,
        boolean array
    ) {
        this(name, type, nullable, enumType, array, null, List.of());
    }

    /**
     * One table's complete row embedded as one component, named after the
     * source's exposed name and holding that table's own columns. The component
     * is non-null, because the source it stands for contributes a row to every
     * result row.
     */
    public static QueryColumn row(String name, String rowTable, List<QueryColumn> rowColumns) {
        return row(name, false, rowTable, rowColumns);
    }

    /**
     * The same embedded row, nullable when the source it stands for may
     * contribute no row, as a left-joined source does when the join finds no
     * match. The row's own columns keep their declared nullability either way,
     * so one table's row record is the same record everywhere.
     */
    public static QueryColumn row(
        String name,
        boolean nullable,
        String rowTable,
        List<QueryColumn> rowColumns
    ) {
        return new QueryColumn(
            name,
            null,
            nullable,
            null,
            false,
            rowTable,
            List.copyOf(rowColumns)
        );
    }

    /** Reports whether this component holds one table's complete row. */
    public boolean row() {
        return rowTable != null;
    }
}
