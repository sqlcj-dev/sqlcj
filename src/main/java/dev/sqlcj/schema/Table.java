package dev.sqlcj.schema;

import java.util.List;

public record Table(
    String name,
    List<Column> columns,
    List<Constraint> constraints
) {

    public Table {
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
    }
}
