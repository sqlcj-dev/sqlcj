package dev.sqlcj.schema;

import java.util.List;

/**
 * One modeled table. {@code partitionOf} names the partitioned table this one
 * is a partition of, or is {@code null} when the table is not a partition,
 * because PostgreSQL applies every column change of a partitioned table to its
 * partitions.
 */
public record Table(
    String name,
    List<Column> columns,
    List<Constraint> constraints,
    String partitionOf
) {

    public Table {
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
    }

    /** A table that is not a partition of another table. */
    public Table(String name, List<Column> columns, List<Constraint> constraints) {
        this(name, columns, constraints, null);
    }
}
