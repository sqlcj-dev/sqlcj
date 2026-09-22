package dev.sqlcj.type;

import dev.sqlcj.schema.ColumnType;

public interface TypeResolver {

    String resolve(ColumnType type);
}
