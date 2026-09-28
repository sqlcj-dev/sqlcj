package dev.sqlcj.schema;

import java.util.List;

/**
 * One enum type declared by a schema source.
 *
 * @param name   the schema's declared name of the type
 * @param labels the labels of the type in PostgreSQL's sort order, which is the
 *               declared order with each added label in the position its
 *               {@code ALTER TYPE ... ADD VALUE} places it
 */
public record EnumType(
    String name,
    List<String> labels
) {

    public EnumType {
        labels = List.copyOf(labels);
    }
}
