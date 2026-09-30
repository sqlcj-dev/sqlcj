package dev.sqlcj.sql;

import java.util.List;

/**
 * Compiled parameters of one SQL source.
 *
 * @param executableSql the source SQL in which every parsed {@code $N} and
 *                      {@code :name} parameter token is replaced by a JDBC
 *                      {@code ?} and every other character is preserved
 * @param indexes       the logical parameter number of each {@code ?} position
 *                      in {@link #executableSql()}, in textual order, which is
 *                      the index of a {@code $N} placeholder and the
 *                      first-occurrence number of a {@code :name} placeholder
 * @param names         the compiled {@code :name} placeholder names in logical
 *                      order, so the name of logical parameter {@code n} is the
 *                      element at {@code n - 1}, and an empty list when the
 *                      source compiled no named placeholder
 * @param uncompiledPlaceholders
 *                      the named placeholders of the parse tree that were not
 *                      replaced, written as the source spells them, such as
 *                      {@code :a.b}, {@code :"x"}, and {@code &x}, each
 *                      reported once in the order they were found
 * @param hasPositionalParameter
 *                      whether the source contains a {@code $N} placeholder
 *                      token, which a named placeholder must not be mixed with
 * @param hasAnonymousParameter
 *                      whether the source contains an anonymous {@code ?}
 *                      placeholder token, which has no parameter to bind
 */
public record SqlParameters(
    String executableSql,
    List<Integer> indexes,
    List<String> names,
    List<String> uncompiledPlaceholders,
    boolean hasPositionalParameter,
    boolean hasAnonymousParameter
) {

    public SqlParameters {
        indexes = List.copyOf(indexes);
        names = List.copyOf(names);
        uncompiledPlaceholders = List.copyOf(uncompiledPlaceholders);
    }
}
