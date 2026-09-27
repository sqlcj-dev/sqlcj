package dev.sqlcj.schema;

/**
 * One column of a schema table. A column whose declared SQL type sqlcj maps
 * carries that {@link ColumnType}; a column of any other type, including every
 * array column, is recorded with its declared type text in
 * {@code unsupportedType} instead, so the schema loads and only a query that
 * uses the column fails. Exactly one of {@code type} and
 * {@code unsupportedType} is non-null.
 */
public record Column(
    String name,
    ColumnType type,
    boolean nullable,
    String unsupportedType
) {

    /** A column of a mapped type. */
    public Column(String name, ColumnType type, boolean nullable) {
        this(name, type, nullable, null);
    }
}
