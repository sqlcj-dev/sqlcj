package dev.sqlcj.sql;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.statement.create.table.ColDataType;
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
    void shouldReplaceReportedNamedParameterSpans() {
        String sql = "SELECT * FROM users WHERE id = :id";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.DOUBLE_COLON, ":", 31),
            token(CCJSqlParserConstants.S_IDENTIFIER, "id", 32)
        );

        assertEquals("SELECT * FROM users WHERE id = ?", parameters.executableSql());
        assertEquals(List.of(1), parameters.indexes());
        assertEquals(List.of("id"), parameters.names());
    }

    /** A name that does not follow its colon directly is other text. */
    @Test
    void shouldIgnoreNameSeparatedFromItsColon() {
        String sql = "SELECT * FROM users WHERE id = : id";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.DOUBLE_COLON, ":", 31),
            token(CCJSqlParserConstants.S_IDENTIFIER, "id", 33)
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());
        assertTrue(parameters.names().isEmpty());
    }

    @Test
    void shouldRejectNamedSpanThatDoesNotHoldTheParameterImage() {
        String sql = "SELECT * FROM users WHERE id = :id";

        Token colon = token(CCJSqlParserConstants.DOUBLE_COLON, ":", 30);
        Token name = token(CCJSqlParserConstants.S_IDENTIFIER, "id", 31);

        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> compile(sql, colon, name)
        );

        assertTrue(
            exception.getMessage()
                .startsWith("Parameter ':id' reported an unusable source position"),
            exception.getMessage()
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

    /**
     * The parser gives the operand of a {@code ::} cast no parse-tree node of
     * its own, so the cast reports the placeholder it casts, through a chained
     * cast of the same operand.
     */
    @Test
    void shouldReportNamedParametersThatAreCastOperands() {
        String sql = "SELECT * FROM users WHERE id = :\"x\"::bigint AND code = &y::int::bigint";

        SqlParameters parameters = compile(
            sql,
            new Token[] { token(CCJSqlParserConstants.S_IDENTIFIER, "SELECT", 0) },
            cast(namedParameter(":", "\"x\"")),
            cast(cast(namedParameter("&", "y")))
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());

        assertEquals(
            List.of(":\"x\"", "&y"),
            parameters.uncompiledPlaceholders()
        );
    }

    /** A cast operand this compiler replaced is not reported again. */
    @Test
    void shouldNotReportCompiledNamedParameterThatIsACastOperand() {
        String sql = "SELECT * FROM users WHERE id = :x::bigint";

        SqlParameters parameters = compile(
            sql,
            new Token[] {
                token(CCJSqlParserConstants.DOUBLE_COLON, ":", 31),
                token(CCJSqlParserConstants.S_IDENTIFIER, "x", 32)
            },
            cast(namedParameter(":", "x"))
        );

        assertEquals("SELECT * FROM users WHERE id = ?::bigint", parameters.executableSql());
        assertEquals(List.of(1), parameters.indexes());
        assertEquals(List.of("x"), parameters.names());
        assertTrue(parameters.uncompiledPlaceholders().isEmpty());
    }

    private SqlParameters compile(String sql, Token... tokens) {
        return compiler.compile(sql, node(tokens));
    }

    /**
     * Compiles a source whose parse tree holds the given expressions beside its
     * tokens, which is how the compiler sees a placeholder the parser reports
     * without a parse-tree node of its own.
     */
    private SqlParameters compile(String sql, Token[] tokens, Expression... expressions) {
        Node root = node(tokens);

        for (int index = 0; index < expressions.length; index++) {
            Node child = new Node(index + 1);

            child.jjtSetValue(expressions[index]);

            root.jjtAddChild(child, index);
        }

        return compiler.compile(sql, root);
    }

    private Node node(Token... tokens) {
        for (int index = 0; index + 1 < tokens.length; index++) {
            tokens[index].next = tokens[index + 1];
        }

        Node node = new Node(0);
        node.jjtSetFirstToken(tokens[0]);
        node.jjtSetLastToken(tokens[tokens.length - 1]);

        return node;
    }

    private CastExpression cast(Expression operand) {
        CastExpression cast = new CastExpression();

        cast.setLeftExpression(operand);
        cast.setColDataType(new ColDataType("bigint"));

        return cast;
    }

    private JdbcNamedParameter namedParameter(String parameterCharacter, String name) {
        return new JdbcNamedParameter(name)
            .setParameterCharacter(parameterCharacter);
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
