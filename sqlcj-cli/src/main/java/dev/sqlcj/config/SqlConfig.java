package dev.sqlcj.config;

/**
 * One configured query group.
 *
 * @param name the required group identity that names the generated repository
 */
public record SqlConfig(
    String name,
    String schema,
    String queries
) {
}
