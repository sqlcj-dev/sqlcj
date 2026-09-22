package dev.sqlcj.sql;

import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the token-span handling of {@link SqlParameterCompiler} with
 * fabricated tokens, which is the only way to reach the guards that protect the
 * compiler from a span the parser cannot describe.
 */
class SqlParameterCompilerTest {

    private final SqlParameterCompiler compiler = new SqlParameterCompiler();

    @Test
    void shouldReplaceReportedParameterSpans() {
        String sql = "SELECT * FROM users WHERE id = $1";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_PARAMETER, "$1", 31)
        );

        assertEquals("SELECT * FROM users WHERE id = ?", parameters.executableSql());
        assertEquals(List.of(1), parameters.indexes());
    }

    @Test
    void shouldIgnoreTokenThatIsNotAParameter() {
        String sql = "SELECT a$1b FROM users";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_IDENTIFIER, "a$1b", 7)
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());
    }

    @Test
    void shouldIgnoreParameterTokenWithoutIndexDigits() {
        String sql = "SELECT * FROM users WHERE id = $";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_PARAMETER, "$", 31)
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());
    }

    @Test
    void shouldRejectSpanThatDoesNotHoldTheTokenImage() {
        String sql = "SELECT * FROM users WHERE id = $1";

        Token parameter = token(CCJSqlParserConstants.S_PARAMETER, "$1", 30);

        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> compile(sql, parameter)
        );

        assertTrue(
            exception.getMessage()
                .startsWith("Parameter '$1' reported an unusable source position"),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectSpanOutsideTheSource() {
        String sql = "SELECT * FROM users WHERE id = $1";

        Token parameter = token(CCJSqlParserConstants.S_PARAMETER, "$1", sql.length());

        assertThrows(
            SqlParseException.class,
            () -> compile(sql, parameter)
        );
    }

    @Test
    void shouldRejectSpansThatAreNotInTextualOrder() {
        String sql = "SELECT * FROM users WHERE id = $1 AND id = $2";

        Token second = token(CCJSqlParserConstants.S_PARAMETER, "$2", 43);
        Token first = token(CCJSqlParserConstants.S_PARAMETER, "$1", 31);

        assertThrows(
            SqlParseException.class,
            () -> compile(sql, second, first)
        );
    }

    private SqlParameters compile(String sql, Token... tokens) {
        for (int index = 0; index + 1 < tokens.length; index++) {
            tokens[index].next = tokens[index + 1];
        }

        SimpleNode node = new SimpleNode(0);
        node.jjtSetFirstToken(tokens[0]);
        node.jjtSetLastToken(tokens[tokens.length - 1]);

        return compiler.compile(sql, node);
    }

    /**
     * Builds a token whose reported position follows the parser convention of
     * counting the first source character as position one.
     */
    private Token token(int kind, String image, int offset) {
        Token token = new Token(kind, image);
        token.absoluteBegin = offset + 1;
        token.absoluteEnd = token.absoluteBegin + image.length();

        return token;
    }
}
