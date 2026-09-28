package dev.sqlcj.type;

import dev.sqlcj.schema.ColumnType;

public class DefaultTypeResolver implements TypeResolver {

    @Override
    public String resolve(ColumnType type) {
        return switch (type) {
            case INTEGER -> "Integer";
            case BIGINT -> "Long";
            case SMALLINT -> "Short";
            case BOOLEAN -> "Boolean";
            case VARCHAR, TEXT, JSON, JSONB -> "String";
            case DATE -> "LocalDate";
            case TIME -> "LocalTime";
            case TIMESTAMP -> "LocalDateTime";
            case TIMESTAMP_WITH_TIME_ZONE -> "OffsetDateTime";
            case DECIMAL -> "BigDecimal";
            case REAL -> "Float";
            case DOUBLE_PRECISION -> "Double";
            case UUID -> "UUID";
            case BYTEA -> "byte[]";
        };
    }
}
