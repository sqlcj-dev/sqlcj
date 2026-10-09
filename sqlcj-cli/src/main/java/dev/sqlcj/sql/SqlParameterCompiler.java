package dev.sqlcj.sql;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Token;

import java.util.ArrayList;
import java.util.List;
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
     * {@link Token#absoluteBegin} and {@link Token#absoluteEnd} count the first
     * source character as position one, so a source offset is one less than the
     * reported position.
     */
    private static final int SOURCE_OFFSET = 1;

    SqlParameters compile(String sql, Node astRoot) {
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

        collectUncompiledPlaceholders(astRoot, names, uncompiled);

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
     * Collects the named placeholders of the parse tree that this compiler did
     * not replace, written as the source spells them, so that an accepted query
     * never keeps such a placeholder in its executable SQL.
     *
     * <p>The parser gives the operand of a {@code ::} cast no parse-tree node
     * of its own, so a cast reports the placeholder it casts, through any
     * further cast of the same operand.
     */
    private void collectUncompiledPlaceholders(Node node, List<String> names, List<String> uncompiled) {
        collectUncompiledPlaceholder(node.jjtGetValue(), names, uncompiled);

        if (node.jjtGetValue() instanceof CastExpression cast) {
            collectUncompiledPlaceholder(castOperand(cast), names, uncompiled);
        }

        for (int child = 0; child < node.jjtGetNumChildren(); child++) {
            collectUncompiledPlaceholders(node.jjtGetChild(child), names, uncompiled);
        }
    }

    /** The operand a cast casts, which is the operand of a chained cast. */
    private Expression castOperand(CastExpression cast) {
        Expression operand = cast.getLeftExpression();

        return operand instanceof CastExpression chained
            ? castOperand(chained)
            : operand;
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
