package dev.sqlcj.sql;

import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParserTest {

    private final SqlParser parser = new SqlParser();

    @Test
    void shouldReplaceOnlyParsedParameterTokens() {
        String sql = """
            SELECT id, '$1 literal' AS "c$1"
            FROM a$1b -- $9 line comment
            WHERE id = $1 /* $8 block comment */
              AND name = $2
            """;

        ParsedSql parsedSql = parser.parse(sql);

        assertInstanceOf(Select.class, parsedSql.statement());

        assertEquals(
            """
                SELECT id, '$1 literal' AS "c$1"
                FROM a$1b -- $9 line comment
                WHERE id = ? /* $8 block comment */
                  AND name = ?
                """,
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(1, 2), parsedSql.parameters().indexes());
    }

    @Test
    void shouldPreserveSourceWithoutParameters() {
        String sql = """
            SELECT id
            FROM users;
            """;

        ParsedSql parsedSql = parser.parse(sql);

        assertEquals(sql, parsedSql.parameters().executableSql());
        assertTrue(parsedSql.parameters().indexes().isEmpty());
    }

    @Test
    void shouldPreserveCarriageReturns() {
        String sql = "UPDATE users\r\nSET name = $2\r\nWHERE id = $1\r\n";

        ParsedSql parsedSql = parser.parse(sql);

        assertEquals(
            "UPDATE users\r\nSET name = ?\r\nWHERE id = ?\r\n",
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(2, 1), parsedSql.parameters().indexes());
    }

    @Test
    void shouldReportRepeatedAndOutOfOrderIndexesInTextualOrder() {
        ParsedSql parsedSql = parser.parse(
            "SELECT * FROM users WHERE id = $2 AND id = $1 AND id = $2"
        );

        assertEquals(
            "SELECT * FROM users WHERE id = ? AND id = ? AND id = ?",
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(2, 1, 2), parsedSql.parameters().indexes());
    }

    @Test
    void shouldCompileParametersOfSupportedWrites() {
        ParsedSql insert = parser.parse("INSERT INTO users (id, name) VALUES ($1, $2)");

        assertEquals(
            "INSERT INTO users (id, name) VALUES (?, ?)",
            insert.parameters().executableSql()
        );

        ParsedSql delete = parser.parse("DELETE FROM users WHERE id = $1");

        assertEquals(
            "DELETE FROM users WHERE id = ?",
            delete.parameters().executableSql()
        );
    }

    @Test
    void shouldReportAnonymousParameterOutsideAnalyzedExpressions() {
        ParsedSql parsedSql = parser.parse("SELECT id FROM users WHERE id = $1 LIMIT ?");

        assertTrue(parsedSql.parameters().hasAnonymousParameter());
    }

    @Test
    void shouldNotReportAnonymousParameterForProtectedText() {
        String sql = """
            SELECT id, '? literal' AS "c?"
            FROM users -- ? line comment
            WHERE id = $1 /* ? block comment */
            """;

        ParsedSql parsedSql = parser.parse(sql);

        assertFalse(parsedSql.parameters().hasAnonymousParameter());

        assertEquals(
            """
                SELECT id, '? literal' AS "c?"
                FROM users -- ? line comment
                WHERE id = ? /* ? block comment */
                """,
            parsedSql.parameters().executableSql()
        );
    }

    @Test
    void shouldRejectDollarQuotedText() {
        assertThrows(
            SqlParseException.class,
            () -> parser.parse("SELECT $$ $1 dollar quoted $$ FROM users WHERE id = $1")
        );
    }

    /**
     * The compiler names the failing query and its header line, so the reason
     * carries the parser's own wording without an exception class name.
     */
    @Test
    void shouldReportSyntaxFailureWithoutExceptionClassNames() {
        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> parser.parse("""
                SELECT id, name
                FROM users
                WHERE id = $1
                  AND AND name = $2""")
        );

        assertEquals(
            "Encountered unexpected token: \"AND\" \"AND\"",
            exception.getMessage()
        );
    }

    /**
     * A lexical failure such as an unterminated string literal reaches the
     * compiler wrapped in exceptions that repeat their cause's class name, so the
     * reason states the lexical wording alone.
     */
    @Test
    void shouldReportLexicalFailureWithoutExceptionClassNames() {
        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> parser.parse("SELECT id, name FROM users WHERE name = 'abc;")
        );

        assertEquals(
            "Lexical error at line 1, column 46."
                + "  Encountered: <EOF> after prefix \"\\'abc;\"",
            exception.getMessage()
        );
        assertFalse(exception.getMessage().contains("net.sf.jsqlparser"));
    }

    /** A block comment ends at its first delimiter and does not nest. */
    @Test
    void shouldPreserveParameterTextInsideBlockCommentWithNestedDelimiter() {
        ParsedSql parsedSql = parser.parse(
            "SELECT id FROM users /* outer /* $8 inner */ WHERE id = $1"
        );

        assertEquals(
            "SELECT id FROM users /* outer /* $8 inner */ WHERE id = ?",
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(1), parsedSql.parameters().indexes());
    }
}
