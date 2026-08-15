package dev.sqlcj.schema;

import java.util.List;

public record Constraint(
        ConstraintType type,
        List<String> columns
) {

    public Constraint {
        columns = List.copyOf(columns);
    }
}
