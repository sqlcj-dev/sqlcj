package dev.sqlcj.config;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/**
 * One configured query group.
 *
 * @param name the required group identity that names the generated repository
 * @param schema the ordered schema sources of the group, each a file or a
 *     directory of migration files
 */
public record SqlConfig(
    String name,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    List<String> schema,
    String queries
) {

    /** One configured schema source, the single-path form of the field. */
    public SqlConfig(String name, String schema, String queries) {
        this(name, schema == null ? null : List.of(schema), queries);
    }
}
