package dev.sqlcj.sql;

import net.sf.jsqlparser.statement.Statement;

/**
 * Syntax-level result of parsing one query SQL source.
 *
 * @param statement  the parsed statement
 * @param parameters the positional parameters compiled from the parsed
 *                   {@code $N} tokens of the same source
 */
public record ParsedSql(
    Statement statement,
    SqlParameters parameters
) {
}
