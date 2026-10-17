package org.sqlcj.type;

import org.sqlcj.schema.ColumnType;

public interface TypeResolver {

    String resolve(ColumnType type);
}
