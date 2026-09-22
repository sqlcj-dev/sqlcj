package dev.sqlcj.sql;

import java.util.List;

/**
 * Compiled positional parameters of one SQL source.
 *
 * @param executableSql the source SQL in which every parsed {@code $N}
 *                      parameter token is replaced by a JDBC {@code ?} and
 *                      every other character is preserved
 * @param indexes       the parameter indexes in the textual order of the
 *                      {@code ?} positions in {@link #executableSql()}
 * @param hasAnonymousParameter
 *                      whether the source contains an anonymous {@code ?}
 *                      placeholder token, which has no index to bind
 */
public record SqlParameters(
    String executableSql,
    List<Integer> indexes,
    boolean hasAnonymousParameter
) {

    public SqlParameters {
        indexes = List.copyOf(indexes);
    }
}
