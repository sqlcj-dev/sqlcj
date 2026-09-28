package dev.sqlcj.schema;

import java.util.List;

public record Schema(
    List<Table> tables,
    List<EnumType> enums
) {

    public Schema {
        tables = List.copyOf(tables);
        enums = List.copyOf(enums);
    }

    /** A schema that declares no enum type. */
    public Schema(List<Table> tables) {
        this(tables, List.of());
    }
}
