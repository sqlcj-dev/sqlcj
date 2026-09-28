package dev.sqlcj.analysis;

import dev.sqlcj.schema.EnumType;

import java.util.List;

/**
 * One analyzed query group and the queries its generated repository exposes,
 * in the order they were declared in the group's query source.
 *
 * @param name  the configured group identity that names the generated repository
 * @param enums the enum types the group's schema declares, which define the
 *              labels of every enum column and parameter of its queries
 */
public record QueryGroupModel(
    String name,
    List<QueryModel> queries,
    List<EnumType> enums
) {

    /** A group whose schema declares no enum type. */
    public QueryGroupModel(String name, List<QueryModel> queries) {
        this(name, queries, List.of());
    }
}
