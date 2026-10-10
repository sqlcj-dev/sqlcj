package dev.sqlcj.sql;

import net.sf.jsqlparser.statement.Statement;

import java.util.List;

/**
 * Syntax-level result of parsing one query SQL source.
 *
 * @param statement    the parsed statement
 * @param parameters   the parameters compiled from the parsed {@code $N} and
 *                     {@code :name} placeholder tokens of the same source
 * @param placeholders the placeholder occurrences of the same source in textual
 *                     order, one per placeholder the parse tree spells, at every
 *                     clause and nesting depth
 */
public record ParsedSql(
    Statement statement,
    SqlParameters parameters,
    List<PlaceholderOccurrence> placeholders
) {

    public ParsedSql {
        placeholders = List.copyOf(placeholders);
    }
}
