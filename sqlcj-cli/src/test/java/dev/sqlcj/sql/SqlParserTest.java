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

    /**
     * Each distinct name is one logical parameter numbered by its first
     * textual occurrence, and every occurrence becomes a {@code ?} of its own.
     */
    @Test
    void shouldCompileNamedParametersByFirstOccurrence() {
        ParsedSql parsedSql = parser.parse(
            "SELECT * FROM users WHERE (name = :term OR bio = :term) AND id > :minId"
        );

        assertEquals(
            "SELECT * FROM users WHERE (name = ? OR bio = ?) AND id > ?",
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(1, 1, 2), parsedSql.parameters().indexes());
        assertEquals(List.of("term", "minId"), parsedSql.parameters().names());
        assertFalse(parsedSql.parameters().hasPositionalParameter());
    }

    /** Names are compared exactly as written, so case distinguishes them. */
    @Test
    void shouldCompileNamesThatDifferInCaseAsDistinctParameters() {
        ParsedSql parsedSql = parser.parse(
            "SELECT * FROM users WHERE name = :term AND bio = :Term"
        );

        assertEquals(List.of(1, 2), parsedSql.parameters().indexes());
        assertEquals(List.of("term", "Term"), parsedSql.parameters().names());
    }

    @Test
    void shouldReportBothPlaceholderFormsOfOneSource() {
        ParsedSql parsedSql = parser.parse(
            "SELECT * FROM users WHERE id = $1 AND name = :name"
        );

        assertTrue(parsedSql.parameters().hasPositionalParameter());
        assertEquals(List.of("name"), parsedSql.parameters().names());
    }

    /**
     * Only a name written directly after a single colon is a parameter, so a
     * quoted name, an ampersand placeholder, a separated name, and a numeric
     * bind stay in the SQL for semantic analysis to reject.
     */
    @Test
    void shouldNotCompileUnsupportedNamedPlaceholderForms() {
        for (
            String sql : List.of(
                "SELECT * FROM users WHERE id = :\"x\"",
                "SELECT * FROM users WHERE id = &x",
                "SELECT * FROM users WHERE id = : x",
                "SELECT * FROM users WHERE id = :1"
            )
        ) {
            ParsedSql parsedSql = parser.parse(sql);

            assertEquals(sql, parsedSql.parameters().executableSql());
            assertTrue(parsedSql.parameters().indexes().isEmpty());
            assertTrue(parsedSql.parameters().names().isEmpty());
        }
    }

    /**
     * A named placeholder the parser reported and this compiler did not replace
     * is reported as the source spells it, so semantic analysis can reject it
     * wherever it appears.
     */
    @Test
    void shouldReportNamedPlaceholdersThatWereNotCompiled() {
        ParsedSql parsedSql = parser.parse(
            "SELECT id FROM users WHERE id = :id AND id = abs(:a.b) ORDER BY :\"x\", &y"
        );

        assertEquals(
            List.of(":a.b", ":\"x\"", "&y"),
            parsedSql.parameters().uncompiledPlaceholders()
        );
    }

    @Test
    void shouldReportNoUncompiledPlaceholderForSupportedForms() {
        ParsedSql parsedSql = parser.parse(
            "SELECT id FROM users WHERE id = $1 AND name = '&x' -- :\"x\""
        );

        assertTrue(parsedSql.parameters().uncompiledPlaceholders().isEmpty());
    }

    @Test
    void shouldPreserveNamedPlaceholderTextThatIsNotAParameter() {
        String sql = """
            SELECT id, ':id literal' AS "c:id"
            FROM users -- :id line comment
            WHERE id = :id /* :id block comment */
            """;

        ParsedSql parsedSql = parser.parse(sql);

        assertEquals(
            """
                SELECT id, ':id literal' AS "c:id"
                FROM users -- :id line comment
                WHERE id = ? /* :id block comment */
                """,
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(1), parsedSql.parameters().indexes());
        assertEquals(List.of("id"), parsedSql.parameters().names());
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

    /** Dollar-quoted text is a literal, so a {@code $N} inside it is text. */
    @Test
    void shouldPreserveParameterTextInsideDollarQuotedText() {
        ParsedSql parsedSql = parser.parse(
            "SELECT $$ $1 dollar quoted $$ FROM users WHERE id = $1"
        );

        assertEquals(
            "SELECT $$ $1 dollar quoted $$ FROM users WHERE id = ?",
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(1), parsedSql.parameters().indexes());
    }

    /**
     * The compiler names the failing query and its header line, so the reason
     * states the unexpected token alone, without a location and without an
     * exception class name.
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
            "Encountered unexpected token: \"AND\"",
            exception.getMessage()
        );
    }

    /** A syntax failure at the end of input has no token image to quote. */
    @Test
    void shouldReportSyntaxFailureAtEndOfInput() {
        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> parser.parse("SELECT id FROM users WHERE id =")
        );

        assertEquals("Encountered unexpected end of input", exception.getMessage());
    }

    /** A reason stays one line, so a token image that spans lines is escaped. */
    @Test
    void shouldEscapeLineBreaksOfTheUnexpectedToken() {
        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> parser.parse("SELECT id FROM users WHERE id = 'x' 'a\nb'")
        );

        assertEquals(
            "Encountered unexpected token: \"'a\\nb'\"",
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
                + "  Encountered: <EOF> after: \"\\'abc;\"",
            exception.getMessage()
        );
        assertFalse(exception.getMessage().contains("net.sf.jsqlparser"));
    }

    /** A block comment nests as in PostgreSQL and ends at its last delimiter. */
    @Test
    void shouldPreserveParameterTextInsideNestedBlockComment() {
        ParsedSql parsedSql = parser.parse(
            "SELECT id FROM users /* outer /* $8 inner */ $9 still comment */ WHERE id = $1"
        );

        assertEquals(
            "SELECT id FROM users /* outer /* $8 inner */ $9 still comment */ WHERE id = ?",
            parsedSql.parameters().executableSql()
        );

        assertEquals(List.of(1), parsedSql.parameters().indexes());
    }
}
