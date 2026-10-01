package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.EnumType;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.create.table.ColDataType;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps one declared SQL type to the type sqlcj models, against the enum types
 * a schema declares.
 *
 * <p>A schema column states its type in a column definition and a query states
 * it in a cast, so both resolve their type here and report an unmapped type
 * with the same text.
 */
public final class ColumnTypeMapping {

    /** One parenthesized type argument group, such as {@code (10, 2)}. */
    private static final Pattern TYPE_ARGUMENTS = Pattern.compile("\\([^)]*\\)");

    /**
     * One declared SQL type and the type sqlcj maps it to.
     *
     * @param type            the mapped type, and {@code null} for a declared
     *                        type sqlcj does not map
     * @param enumType        the schema's declared name of the enum type the
     *                        declaration names when {@code type} is
     *                        {@link ColumnType#ENUM}, and {@code null}
     *                        otherwise
     * @param array           whether the declaration is a one-dimensional
     *                        array of {@code type}
     * @param blankPadded     whether the declared spelling is the blank-padded
     *                        character type
     * @param unsupportedType the canonical declared type text of a type sqlcj
     *                        does not map, including its array dimensions, and
     *                        {@code null} for a mapped type
     * @param typeName        the canonical spelling of the declared element
     *                        type, without its array dimensions
     */
    public record MappedType(
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        String unsupportedType,
        String typeName
    ) {

        /** Reports whether sqlcj maps the declared type. */
        public boolean mapped() {
            return type != null;
        }
    }

    /**
     * Maps one declared SQL type, recording a type sqlcj cannot map with its
     * declared type text instead of failing. A declaration of exactly one array
     * dimension is an array of its declared element type; a declaration of more
     * dimensions, and an array whose element type has no array mapping, is
     * recorded like any other unmapped type.
     */
    public MappedType map(ColDataType declaredType, List<EnumType> enums) {
        String typeName = typeName(declaredType);
        int arrayDimensions = arrayDimensions(declaredType);
        boolean array = arrayDimensions == 1;

        if (arrayDimensions <= 1) {
            ColumnType type = columnType(typeName);

            if (type != null) {
                if (!array || isArrayElementType(type)) {
                    return new MappedType(
                        type,
                        null,
                        array,
                        isBlankPadded(typeName),
                        null,
                        typeName
                    );
                }
            } else {
                EnumType enumType = declaredEnum(declaredType, enums);

                if (enumType != null) {
                    return new MappedType(
                        ColumnType.ENUM,
                        enumType.name(),
                        array,
                        false,
                        null,
                        typeName
                    );
                }
            }
        }

        return new MappedType(
            null,
            null,
            false,
            false,
            typeName + "[]".repeat(arrayDimensions),
            typeName
        );
    }

    /**
     * Reports whether a declared spelling is the blank-padded character type,
     * which PostgreSQL names {@code bpchar} and maps like {@code VARCHAR}. The
     * distinction is kept because the two names are not interchangeable where
     * PostgreSQL resolves an array type from its element's name.
     */
    private boolean isBlankPadded(String typeName) {
        return switch (typeName) {
            case "CHAR", "CHARACTER" -> true;
            default -> false;
        };
    }

    /**
     * Reports whether an array of a mapped type is mapped as well.
     * {@code BYTEA}, {@code JSON}, and {@code JSONB} arrays are recorded with
     * their declared type instead, because their elements are bound and read as
     * text or bytes rather than as a value of a mapped element type.
     */
    private boolean isArrayElementType(ColumnType type) {
        return type != ColumnType.BYTEA
            && type != ColumnType.JSON
            && type != ColumnType.JSONB;
    }

    /**
     * The enum type an unmapped declared type names, or {@code null} when the
     * schema declares no such type. The declared type is matched without its
     * SQL identifier delimiters and case-insensitively, as PostgreSQL resolves
     * an unquoted type name.
     */
    private EnumType declaredEnum(ColDataType declaredType, List<EnumType> enums) {
        String typeName = MultiPartName.unquote(declaredType.getDataType());

        return enums.stream()
            .filter(enumType -> enumType.name().equalsIgnoreCase(typeName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Reports how many array dimensions a declaration states. The parser
     * reports an array's dimensions separately from its element type, so a
     * declaration with any dimension is an array of the reported type.
     */
    private int arrayDimensions(ColDataType declaredType) {
        List<Integer> arrayData = declaredType.getArrayData();

        return arrayData == null
            ? 0
            : arrayData.size();
    }

    /**
     * Returns the canonical spelling of a declared SQL type: upper case,
     * without type arguments such as a length or a precision, and with single
     * spaces between the remaining words.
     */
    private String typeName(ColDataType declaredType) {
        String dataType = declaredType
            .getDataType()
            .toUpperCase(Locale.ROOT);

        return TYPE_ARGUMENTS
            .matcher(dataType)
            .replaceAll(" ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** The mapped type of declared spelling, or {@code null} if unmapped. */
    private ColumnType columnType(String typeName) {
        return switch (typeName) {
            case "INTEGER", "INT", "INT4", "SERIAL", "SERIAL4" -> ColumnType.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL", "SERIAL8" -> ColumnType.BIGINT;
            case "SMALLINT", "INT2", "SMALLSERIAL", "SERIAL2" -> ColumnType.SMALLINT;
            case "BOOLEAN", "BOOL" -> ColumnType.BOOLEAN;
            case "VARCHAR", "CHARACTER VARYING", "CHAR", "CHARACTER" -> ColumnType.VARCHAR;
            case "TEXT" -> ColumnType.TEXT;
            case "DATE" -> ColumnType.DATE;
            case "TIME", "TIME WITHOUT TIME ZONE" -> ColumnType.TIME;
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> ColumnType.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> ColumnType.TIMESTAMP_WITH_TIME_ZONE;
            case "DECIMAL", "NUMERIC" -> ColumnType.DECIMAL;
            case "REAL", "FLOAT4" -> ColumnType.REAL;
            case "DOUBLE PRECISION", "FLOAT8" -> ColumnType.DOUBLE_PRECISION;
            case "UUID" -> ColumnType.UUID;
            case "BYTEA" -> ColumnType.BYTEA;
            case "JSON" -> ColumnType.JSON;
            case "JSONB" -> ColumnType.JSONB;
            default -> null;
        };
    }
}
