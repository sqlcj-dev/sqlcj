package org.sqlcj.sql;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.statement.select.Select;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Compiles the positional {@code $N} and named {@code :name} parameters of a
 * parsed SQL source into JDBC {@code ?} markers.
 *
 * <p>Only tokens that the SQL parser itself reported as parameter tokens are
 * replaced. Every other character is copied from the source, so placeholder
 * text inside string literals, quoted identifiers, comments, and identifiers
 * stays byte-for-byte unchanged.
 *
 * <p>Each distinct placeholder name is one logical parameter, numbered by its
 * first textual occurrence, and every occurrence of that name becomes a
 * {@code ?} of its own. A placeholder form this compiler does not recognize,
 * such as a qualified, quoted, or {@code &name} placeholder, stays in the SQL
 * and is reported as written for semantic analysis to reject, as is an
 * anonymous {@code ?} placeholder, which has no parameter to bind.
 *
 * <p>The same walk reports the placeholder occurrences of the source in
 * textual order, each with the cast that casts it and with where it stands as
 * the source writes it, which is the syntax-level order in which semantic
 * analysis binds them.
 */
final class SqlParameterCompiler {

    /**
     * A positional parameter image. The digits are bounded because an index
     * outside the {@code int} range is not a parameter this compiler can
     * report; such a token stays in the SQL and is rejected later as an
     * unaccounted parameter.
     */
    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile("\\$\\d{1,9}");

    /**
     * A named parameter name, which is an unquoted identifier of ASCII letters,
     * digits, and underscores that does not start with a digit. Names are
     * compared exactly as written.
     */
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The image of the token that opens a named parameter. The parser reports
     * the cast operator {@code ::} as a token of its own, so only a single
     * colon opens a name.
     */
    private static final String NAME_PREFIX = ":";

    /**
     * The image of an anonymous placeholder token. The parser reports it as a
     * token of its own, so a {@code ?} inside a string literal, a quoted
     * identifier, or a comment is not reported here, and the JSON operators
     * {@code ?|} and {@code ?&} are reported with their own images.
     */
    private static final String ANONYMOUS_PARAMETER = "?";

    /**
     * The clause keywords whose own image names the clause a placeholder stands
     * alone in, compared ignoring case and written in upper case.
     */
    private static final List<String> CLAUSE_KEYWORDS = List.of(
        "WHERE",
        "HAVING",
        "LIMIT",
        "OFFSET",
        "FETCH",
        "SELECT",
        "VALUES",
        "SET",
        "RETURNING"
    );

    /** The image that opens a parenthesized group. */
    private static final String OPEN_PARENTHESIS = "(";

    /** The image that closes a parenthesized group. */
    private static final String CLOSE_PARENTHESIS = ")";

    /**
     * {@link Token#absoluteBegin} and {@link Token#absoluteEnd} count the first
     * source character as position one, so a source offset is one less than the
     * reported position.
     */
    private static final int SOURCE_OFFSET = 1;

    /**
     * Compiles the parameters of one parsed source and collects its placeholder
     * occurrences into {@code placeholders}, in the textual order of the
     * {@code ?} positions of the compiled SQL.
     */
    SqlParameters compile(String sql, Node astRoot, List<PlaceholderOccurrence> placeholders) {
        if (astRoot == null) {
            throw new SqlParseException("SQL parse tree is unavailable.");
        }

        Token firstToken = astRoot.jjtGetFirstToken();
        Token lastToken = astRoot.jjtGetLastToken();

        if (firstToken == null || lastToken == null) {
            throw new SqlParseException("SQL parse tree has no tokens.");
        }

        StringBuilder executableSql = new StringBuilder();
        List<Integer> indexes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean anonymous = false;
        boolean positional = false;
        int copied = 0;

        for (Token token = firstToken; token != null; token = token.next) {
            if (isAnonymousParameter(token)) {
                anonymous = true;
            }

            if (isPositionalParameter(token)) {
                int begin = requireSpan(
                    sql,
                    token.image,
                    token.absoluteBegin,
                    token.absoluteEnd,
                    copied
                );

                executableSql
                    .append(sql, copied, begin)
                    .append('?');

                copied = begin + token.image.length();

                indexes.add(Integer.parseInt(token.image.substring(1)));

                positional = true;
            } else if (token != lastToken && isNamedParameter(token, token.next)) {
                Token name = token.next;
                String image = token.image + name.image;

                int begin = requireSpan(
                    sql,
                    image,
                    token.absoluteBegin,
                    name.absoluteEnd,
                    copied
                );

                executableSql
                    .append(sql, copied, begin)
                    .append('?');

                copied = begin + image.length();

                indexes.add(numberOf(name.image, names));

                token = name;
            }

            if (token == lastToken) {
                break;
            }
        }

        executableSql.append(sql, copied, sql.length());

        List<String> uncompiled = new ArrayList<>();

        collectPlaceholders(astRoot, names, uncompiled, placeholders);

        return new SqlParameters(
            executableSql.toString(),
            indexes,
            names,
            uncompiled,
            positional,
            anonymous
        );
    }

    /**
     * Walks the parse tree in pre-order, collecting the placeholder occurrences
     * of the source in textual order and the named placeholders this compiler
     * did not replace, written as the source spells them, so that an accepted
     * query never keeps such a placeholder in its executable SQL.
     *
     * <p>The parser gives the operand of a {@code ::} cast no parse-tree node
     * of its own, so a cast reports the placeholder it casts, through any
     * further cast of the same operand.
     */
    private void collectPlaceholders(
        Node node,
        List<String> names,
        List<String> uncompiled,
        List<PlaceholderOccurrence> placeholders
    ) {
        Object value = node.jjtGetValue();

        collectUncompiledPlaceholder(value, names, uncompiled);

        if (value instanceof CastExpression cast) {
            CastExpression innermost = innermostCast(cast);
            Expression operand = innermost.getLeftExpression();

            collectUncompiledPlaceholder(operand, names, uncompiled);
            collectPlaceholder(operand, innermost, node, placeholders);
        }

        if (value instanceof Expression expression) {
            collectPlaceholder(expression, null, node, placeholders);
        }

        for (int child = 0; child < node.jjtGetNumChildren(); child++) {
            collectPlaceholders(node.jjtGetChild(child), names, uncompiled, placeholders);
        }
    }

    /**
     * The cast a chain of casts applies first, which is the cast whose operand
     * a chain such as {@code :x::text::int} casts directly.
     */
    private CastExpression innermostCast(CastExpression cast) {
        return cast.getLeftExpression() instanceof CastExpression chained
            ? innermostCast(chained)
            : cast;
    }

    /**
     * Collects one parse-tree value when it is a placeholder, in the order the
     * walk reaches it.
     *
     * <p>One expression can hang under two parse-tree nodes, so a placeholder
     * is recorded once, identified by the expression itself, with where it
     * stands under the node that reports it first, and the cast that casts it
     * is kept whichever node reports it first.
     */
    private void collectPlaceholder(
        Object value,
        CastExpression cast,
        Node node,
        List<PlaceholderOccurrence> placeholders
    ) {
        if (!(value instanceof JdbcParameter) && !(value instanceof JdbcNamedParameter)) {
            return;
        }

        Expression placeholder = (Expression) value;

        for (int index = 0; index < placeholders.size(); index++) {
            PlaceholderOccurrence collected = placeholders.get(index);

            if (collected.placeholder() == placeholder) {
                if (cast != null && collected.cast() == null) {
                    placeholders.set(
                        index,
                        new PlaceholderOccurrence(placeholder, cast, collected.enclosing())
                    );
                }

                return;
            }
        }

        placeholders.add(
            new PlaceholderOccurrence(placeholder, cast, enclosing(node, placeholder))
        );
    }

    /**
     * Where one placeholder stands, written as the source writes it: the
     * smallest expression the parse tree encloses it in, or, when the parse
     * tree encloses it in none, the clause it stands alone in.
     */
    private String enclosing(Node node, Expression placeholder) {
        String expression = enclosingExpression(node, placeholder);

        return expression == null
            ? clauseKeyword(node)
            : expression;
    }

    /**
     * The smallest expression the parse tree reports around one placeholder,
     * written on one line, and {@code null} when the placeholder stands alone
     * in its clause.
     *
     * <p>A node of no value, a node of the placeholder itself, and an
     * expression list, such as the {@code (:a, :b)} of an {@code IN} predicate,
     * name nothing of their own, so the search steps through them to the
     * expression around them. A query, a subquery included, and a node that is
     * not an expression at all, such as a select item or a join, open the
     * clause the placeholder stands in.
     */
    private String enclosingExpression(Node node, Expression placeholder) {
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.jjtGetParent()) {
            Object value = ancestor.jjtGetValue();

            if (value == null || value == placeholder || value instanceof ExpressionList<?>) {
                continue;
            }

            if (value instanceof Select || !(value instanceof Expression)) {
                return null;
            }

            return writtenText(ancestor.jjtGetFirstToken(), ancestor.jjtGetLastToken());
        }

        return null;
    }

    /**
     * One span of tokens as the source writes it, on one line: each token
     * carries its own source image, and whatever separates two tokens, which is
     * whitespace, a comment, or both, is written as one space.
     */
    private String writtenText(Token first, Token last) {
        StringBuilder text = new StringBuilder();
        int previousEnd = -1;

        for (Token token = first; token != null; token = token.next) {
            if (previousEnd >= 0 && token.absoluteBegin > previousEnd) {
                text.append(' ');
            }

            text.append(token.image);

            if (token == last) {
                break;
            }

            previousEnd = token.absoluteEnd;
        }

        return text.toString();
    }

    /**
     * The keyword of the clause one placeholder stands alone in, which is the
     * nearest clause keyword written before it at its own parenthesis depth or
     * at a depth enclosing it, in upper case.
     *
     * <p>The scan therefore forgets a keyword of a parenthesized group that
     * closed before the placeholder and keeps one written outside a group the
     * placeholder stands inside. A source whose clause no keyword of the subset
     * opens is named by the keyword that opens the statement.
     */
    private String clauseKeyword(Node node) {
        Token placeholder = node.jjtGetFirstToken();
        Token first = statementRoot(node).jjtGetFirstToken();

        List<String> enclosingKeywords = new ArrayList<>();
        String keyword = first.image.toUpperCase(Locale.ROOT);
        Token previous = null;

        for (Token token = first; token != null && token != placeholder; token = token.next) {
            if (OPEN_PARENTHESIS.equals(token.image)) {
                enclosingKeywords.add(keyword);
            } else if (CLOSE_PARENTHESIS.equals(token.image)) {
                if (!enclosingKeywords.isEmpty()) {
                    keyword = enclosingKeywords.remove(enclosingKeywords.size() - 1);
                }
            } else {
                String clause = clauseKeyword(token, previous);

                if (clause != null) {
                    keyword = clause;
                }
            }

            previous = token;
        }

        return keyword;
    }

    /**
     * The clause one token opens, and {@code null} when the token opens none.
     * {@code ON} opens a join condition on its own and a distinct list after
     * {@code DISTINCT}, and {@code BY} opens the clause {@code ORDER} or
     * {@code GROUP} names.
     */
    private String clauseKeyword(Token token, Token previous) {
        String image = token.image.toUpperCase(Locale.ROOT);

        if (CLAUSE_KEYWORDS.contains(image)) {
            return image;
        }

        return switch (image) {
            case "ON" -> isImage(previous, "DISTINCT")
                ? "DISTINCT ON"
                : "ON";
            case "BY" -> byKeyword(previous);
            default -> null;
        };
    }

    /** The clause a {@code BY} keyword opens, which the token before it names. */
    private String byKeyword(Token previous) {
        if (isImage(previous, "ORDER")) {
            return "ORDER BY";
        }

        return isImage(previous, "GROUP")
            ? "GROUP BY"
            : null;
    }

    private boolean isImage(Token token, String image) {
        return token != null && image.equalsIgnoreCase(token.image);
    }

    /** The parse-tree root of the statement one node belongs to. */
    private Node statementRoot(Node node) {
        Node root = node;

        while (root.jjtGetParent() != null) {
            root = root.jjtGetParent();
        }

        return root;
    }

    /**
     * Collects one parse-tree value when it is a named placeholder this
     * compiler did not replace, once per placeholder as the source spells it.
     */
    private void collectUncompiledPlaceholder(Object value, List<String> names, List<String> uncompiled) {
        if (!(value instanceof JdbcNamedParameter named) || isCompiled(named, names)) {
            return;
        }

        String placeholder = named.getParameterCharacter() + named.getName();

        if (!uncompiled.contains(placeholder)) {
            uncompiled.add(placeholder);
        }
    }

    /**
     * Reports whether a named placeholder the parser produced is one this
     * compiler replaced, which requires both its colon and its name to be the
     * supported form.
     */
    private boolean isCompiled(JdbcNamedParameter named, List<String> names) {
        return NAME_PREFIX.equals(named.getParameterCharacter())
            && names.contains(named.getName());
    }

    private boolean isAnonymousParameter(Token token) {
        return ANONYMOUS_PARAMETER.equals(token.image);
    }

    private boolean isPositionalParameter(Token token) {
        return token.kind == CCJSqlParserConstants.S_PARAMETER
            && token.image != null
            && POSITIONAL_PARAMETER.matcher(token.image).matches();
    }

    /**
     * Reports whether a colon token and the token after it are one named
     * parameter, which requires the name to follow the colon directly. A name
     * separated from its colon, a qualified or quoted name, and a name that is
     * not an unquoted identifier are other text, which stays in the SQL.
     */
    private boolean isNamedParameter(Token colon, Token name) {
        return colon.kind == CCJSqlParserConstants.DOUBLE_COLON
            && NAME_PREFIX.equals(colon.image)
            && name != null
            && name.image != null
            && name.absoluteBegin == colon.absoluteEnd
            && PARAMETER_NAME.matcher(name.image).matches();
    }

    /**
     * Reports the logical parameter number of a placeholder name: the number
     * the name already has, or the next number, which records the name so that
     * distinct names are numbered by their first occurrence.
     */
    private int numberOf(String name, List<String> names) {
        int index = names.indexOf(name);

        if (index >= 0) {
            return index + 1;
        }

        names.add(name);

        return names.size();
    }

    /**
     * Returns the source offset of a parameter after requiring that its
     * reported span follows the previous replacement, stays inside the source,
     * and holds exactly the parameter image.
     */
    private int requireSpan(String sql, String image, int absoluteBegin, int absoluteEnd, int copied) {
        int begin = absoluteBegin - SOURCE_OFFSET;
        int end = absoluteEnd - SOURCE_OFFSET;

        boolean valid = begin >= copied
            && end <= sql.length()
            && end - begin == image.length()
            && sql.startsWith(image, begin);

        if (!valid) {
            throw new SqlParseException(
                "Parameter '%s' reported an unusable source position: [%d, %d)"
                    .formatted(image, begin, end)
            );
        }

        return begin;
    }
}
