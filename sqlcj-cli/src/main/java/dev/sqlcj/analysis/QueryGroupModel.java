package dev.sqlcj.analysis;

import java.util.List;

/**
 * One analyzed query group and the queries its generated repository exposes,
 * in the order they were declared in the group's query source.
 *
 * @param name the configured group identity that names the generated repository
 */
public record QueryGroupModel(
    String name,
    List<QueryModel> queries
) {
}
