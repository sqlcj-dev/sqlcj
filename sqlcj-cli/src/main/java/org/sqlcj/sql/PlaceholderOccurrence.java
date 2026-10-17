package org.sqlcj.sql;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;

/**
 * One placeholder token of a parsed SQL source, as the parse tree spells it.
 *
 * <p>An occurrence is syntax, not a type: it reports where a placeholder stands
 * and whether the query casts it, and leaves to semantic analysis which
 * parameter it binds and of which type.
 *
 * @param placeholder the placeholder expression the parser produced, which
 *                    identifies this occurrence
 * @param cast        the cast whose direct operand the placeholder is, which is
 *                    the innermost cast of a chain such as
 *                    {@code :x::text::int}, and {@code null} when the
 *                    placeholder is not cast directly
 * @param enclosing   where the placeholder stands, written as the query writes
 *                    it: the smallest enclosing expression the parse tree
 *                    reports, on one line, or the keyword of the clause the
 *                    placeholder stands alone in, in upper case
 */
public record PlaceholderOccurrence(
    Expression placeholder,
    CastExpression cast,
    String enclosing
) {
}
