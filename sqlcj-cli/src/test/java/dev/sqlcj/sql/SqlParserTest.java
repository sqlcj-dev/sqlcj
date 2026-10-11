package dev.sqlcj.sql;

import net.sf.jsqlparser.expression.JdbcNamedParameter;
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

    /**
     * One occurrence is reported per placeholder the source spells, in textual
     * order at every clause and nesting depth, each with the cast whose direct
     * operand it is.
     */
    @Test
    void shouldReportPlaceholderOccurrencesInTextualOrder() {
        ParsedSql parsedSql = parser.parse("""
            WITH recent AS (SELECT id FROM orders WHERE total = :amount::numeric)
            SELECT :label::text AS label, id
            FROM users
            WHERE id = :id
              AND EXISTS (SELECT 1 FROM tasks WHERE tasks.name = CAST(:task AS text))
            ORDER BY id
            OFFSET :skip::int
            LIMIT :pageSize::int""");

        assertEquals(
            List.of("amount", "label", "id", "task", "skip", "pageSize"),
            placeholderNames(parsedSql)
        );

        assertEquals(
            List.of("numeric", "text", "none", "text", "int", "int"),
            castTypes(parsedSql)
        );
    }

    /**
     * Every occurrence of one placeholder is reported, and only a placeholder a
     * cast casts directly carries a cast, which for a chain of casts is the
     * cast that applies first.
     */
    @Test
    void shouldReportTheCastThatCastsAPlaceholderDirectly() {
        ParsedSql parsedSql = parser.parse(
            "SELECT id FROM users WHERE id = :a::text::int AND id = (:b)::int AND id = :a"
        );

        assertEquals(List.of("a", "b", "a"), placeholderNames(parsedSql));
        assertEquals(List.of("text", "none", "none"), castTypes(parsedSql));
        assertEquals(List.of(1, 2, 1), parsedSql.parameters().indexes());
    }

    /**
     * Where a placeholder stands is the smallest expression the parse tree
     * reports around it, written as the source writes it: an {@code IN} list is
     * not an expression of its own, so its placeholders stand in the whole
     * {@code IN} predicate, and an expression written across lines is written
     * on one line, with whatever separates two tokens written as one space.
     */
    @Test
    void shouldReportTheEnclosingExpressionOfAPlaceholder() {
        assertEquals(
            List.of("lower(name) = :name"),
            enclosings("SELECT id FROM users WHERE lower(name) = :name")
        );

        assertEquals(
            List.of("id IN (:a, :b)", "id IN (:a, :b)"),
            enclosings("SELECT id FROM users WHERE id IN (:a, :b)")
        );

        assertEquals(
            List.of("name LIKE '%' || :q || '%'"),
            enclosings("""
                SELECT id
                FROM users
                WHERE name LIKE '%' ||   -- the prefix
                      :q || '%'""")
        );
    }

    /**
     * A placeholder the parse tree reports no expression around stands alone in
     * its clause, which the keyword that opens that clause names in upper case.
     */
    @Test
    void shouldReportTheClauseOfAPlaceholderThatStandsAlone() {
        assertEquals(
            List.of("ORDER BY"),
            enclosings("SELECT id FROM users ORDER BY name, :sort")
        );

        assertEquals(
            List.of("GROUP BY"),
            enclosings("SELECT id FROM users GROUP BY :grouping")
        );

        assertEquals(
            List.of("HAVING"),
            enclosings("SELECT id FROM users GROUP BY name HAVING $1")
        );

        assertEquals(
            List.of("FETCH"),
            enclosings("SELECT id FROM users FETCH FIRST :rows ROWS ONLY")
        );

        assertEquals(
            List.of("ON"),
            enclosings("SELECT u.id FROM users u JOIN profiles p ON :matches")
        );

        assertEquals(
            List.of("DISTINCT ON"),
            enclosings("SELECT DISTINCT ON (:sort) id FROM users")
        );

        assertEquals(
            List.of("WHERE"),
            enclosings("UPDATE users SET name = 'x' WHERE :matches")
        );
    }

    /** Where each placeholder occurrence of a source stands. */
    private List<String> enclosings(String sql) {
        return parser.parse(sql).placeholders().stream()
            .map(PlaceholderOccurrence::enclosing)
            .toList();
    }

    private List<String> placeholderNames(ParsedSql parsedSql) {
        return parsedSql.placeholders().stream()
            .map(occurrence -> ((JdbcNamedParameter) occurrence.placeholder()).getName())
            .toList();
    }

    /** The declared cast type of each occurrence, and {@code none} for none. */
    private List<String> castTypes(ParsedSql parsedSql) {
        return parsedSql.placeholders().stream()
            .map(PlaceholderOccurrence::cast)
            .map(
                cast -> cast == null
                    ? "none"
                    : cast.getColDataType().getDataType()
            )
            .toList();
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
