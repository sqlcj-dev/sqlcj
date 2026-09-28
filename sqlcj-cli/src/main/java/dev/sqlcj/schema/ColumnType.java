package dev.sqlcj.schema;

public enum ColumnType {
    INTEGER,
    BIGINT,
    SMALLINT,
    BOOLEAN,
    VARCHAR,
    TEXT,
    DATE,
    TIME,
    TIMESTAMP,
    TIMESTAMP_WITH_TIME_ZONE,
    DECIMAL,
    REAL,
    DOUBLE_PRECISION,
    UUID,
    BYTEA,
    JSON,
    JSONB,

    /**
     * A column of a declared enum type, whose Java type is the enum generated
     * for the type named by the column.
     */
    ENUM
}
