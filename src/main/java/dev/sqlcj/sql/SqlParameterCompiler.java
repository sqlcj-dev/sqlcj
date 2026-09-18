package dev.sqlcj.sql;

import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.parser.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compiles the positional {@code $N} parameters of a parsed SQL source into
 * JDBC {@code ?} markers.
 *
 * <p>Only tokens that the SQL parser itself reported as parameter tokens are
 * replaced. Every other character is copied from the source, so placeholder
 * text inside string literals, quoted identifiers, comments, and identifiers
 * stays byte-for-byte unchanged.
 *
 * <p>An anonymous {@code ?} placeholder has no index to bind, so it is
 * reported instead of replaced and is rejected by semantic analysis.
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
        if (!(astRoot instanceof SimpleNode node)) {
            throw new SqlParseException("SQL parse tree is unavailable.");
        }

        Token firstToken = node.jjtGetFirstToken();
        Token lastToken = node.jjtGetLastToken();

        if (firstToken == null || lastToken == null) {
            throw new SqlParseException("SQL parse tree has no tokens.");
        }

        StringBuilder executableSql = new StringBuilder();
        List<Integer> indexes = new ArrayList<>();
        boolean anonymous = false;
        int copied = 0;

        for (Token token = firstToken; token != null; token = token.next) {
            if (isAnonymousParameter(token)) {
                anonymous = true;
            }

            if (isPositionalParameter(token)) {
                int begin = requireSpan(sql, token, copied);

                executableSql
                    .append(sql, copied, begin)
                    .append('?');

                copied = begin + token.image.length();

                indexes.add(Integer.parseInt(token.image.substring(1)));
            }

            if (token == lastToken) {
                break;
            }
        }

        executableSql.append(sql, copied, sql.length());

        return new SqlParameters(executableSql.toString(), indexes, anonymous);
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
     * Returns the source offset of a parameter token after requiring that its
     * reported span follows the previous replacement, stays inside the source,
     * and holds exactly the token image.
     */
    private int requireSpan(String sql, Token token, int copied) {
        int begin = token.absoluteBegin - SOURCE_OFFSET;
        int end = token.absoluteEnd - SOURCE_OFFSET;

        boolean valid = begin >= copied
            && end <= sql.length()
            && end - begin == token.image.length()
            && sql.startsWith(token.image, begin);

        if (!valid) {
            throw new SqlParseException(
                "Parameter '%s' reported an unusable source position: [%d, %d)"
                    .formatted(token.image, begin, end)
            );
        }

        return begin;
    }
}
