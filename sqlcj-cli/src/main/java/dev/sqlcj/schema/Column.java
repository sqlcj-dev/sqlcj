package dev.sqlcj.schema;

/**
 * One column of a schema table. A column whose declared SQL type sqlcj maps
 * carries that {@link ColumnType}; a column of any other type, including every
 * array column, is recorded with its declared type text in
 * {@code unsupportedType} instead, so the schema loads and only a query that
 * uses the column fails. Exactly one of {@code type} and
 * {@code unsupportedType} is non-null.
 *
 * <p>A column of a declared enum type carries {@link ColumnType#ENUM} and the
 * schema's declared name of that type in {@code enumType}, which is the only
 * column kind whose Java type is not decided by {@code type} alone.
 */
public record Column(
    String name,
    ColumnType type,
    boolean nullable,
    String unsupportedType,
    String enumType
) {

    /** A column of a mapped type. */
    public Column(String name, ColumnType type, boolean nullable) {
        this(
            name,
            type,
            nullable,
            null,
            null
        );
    }

    /** A column of a mapped type, or one recorded with its declared type. */
    public Column(String name, ColumnType type, boolean nullable, String unsupportedType) {
        this(
            name,
            type,
            nullable,
            unsupportedType,
            null
        );
    }
}
