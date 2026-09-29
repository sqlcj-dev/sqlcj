package dev.sqlcj.schema;

/**
 * One column of a schema table. A column whose declared SQL type sqlcj maps
 * carries that {@link ColumnType}; a column of any other type is recorded with
 * its declared type text in {@code unsupportedType} instead, so the schema loads
 * and only a query that uses the column fails. Exactly one of {@code type} and
 * {@code unsupportedType} is non-null.
 *
 * <p>A column of a declared enum type carries {@link ColumnType#ENUM} and the
 * schema's declared name of that type in {@code enumType}, which is the only
 * column kind whose Java type is not decided by {@code type} alone.
 *
 * <p>A column declared as a one-dimensional array of a mapped element type
 * carries that element's {@link ColumnType} with {@code array} set, and its
 * Java type is a list of the element's Java type.
 *
 * <p>A column declared with the blank-padded {@code CHAR} or {@code CHARACTER}
 * spelling carries {@link ColumnType#VARCHAR} with {@code blankPadded} set,
 * which keeps PostgreSQL's own name of the type, {@code bpchar}, knowable where
 * that name is bound. The spelling does not change the column's Java type.
 */
public record Column(
    String name,
    ColumnType type,
    boolean nullable,
    String unsupportedType,
    String enumType,
    boolean array,
    boolean blankPadded
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

    /** A scalar column, whose declared type may name an enum type. */
    public Column(
        String name,
        ColumnType type,
        boolean nullable,
        String unsupportedType,
        String enumType
    ) {
        this(
            name,
            type,
            nullable,
            unsupportedType,
            enumType,
            false
        );
    }

    /** A column of a type whose declared spelling is not blank padded. */
    public Column(
        String name,
        ColumnType type,
        boolean nullable,
        String unsupportedType,
        String enumType,
        boolean array
    ) {
        this(
            name,
            type,
            nullable,
            unsupportedType,
            enumType,
            array,
            false
        );
    }
}
