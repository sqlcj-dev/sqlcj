package dev.sqlcj.schema;

import java.util.List;

public record Schema(
    List<Table> tables
) {

    public Schema {
        tables = List.copyOf(tables);
    }
}
