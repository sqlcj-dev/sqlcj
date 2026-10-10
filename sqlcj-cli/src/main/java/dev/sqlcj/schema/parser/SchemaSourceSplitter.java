package dev.sqlcj.schema.parser;

import dev.sqlcj.sql.SqlParseReason;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserTokenManager;
import net.sf.jsqlparser.parser.SimpleCharStream;
import net.sf.jsqlparser.parser.StringProvider;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.parser.TokenMgrException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits one schema source into the statements it states, so that each of them
 * is parsed alone and a statement the parser cannot read fails only itself.
 *
 * <p>The boundaries come from the SQL parser's own token manager, which reports
 * a statement separator outside quoted strings, quoted identifiers, comments,
 * and the untagged {@code $$ ... $$} literal it lexes as one token. A tagged
 * delimiter such as {@code $body$} is lexed as an ordinary word instead, so a
 * tagged body is kept whole here by pairing its delimiters, as PostgreSQL pairs
 * them.
 */
final class SchemaSourceSplitter {

    /**
     * The opening delimiter of a tagged dollar-quoted string, which the token
     * manager may glue to the text that follows it.
     */
    private static final Pattern DOLLAR_QUOTE_TAG = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*\\$");

    private SchemaSourceSplitter() {
    }

    /**
     * One statement of a schema source: its own SQL text including the
     * separator, the line and column it begins on in the whole source, and the
     * images of its tokens without that separator.
     */
    record SourceStatement(String sql, int line, int column, List<String> tokens) {
    }

    /**
     * The statements of one schema source, in order. A separator with no tokens
     * before it states no statement, and text after the last separator is one
     * statement of its own, which the parser then rejects as PostgreSQL does.
     *
     * <p>A psql meta-command line, such as the {@code \restrict} lines recent
     * {@code pg_dump} releases write, is blanked before lexing, because its
     * first character is a lexical error. Blanking keeps every later line and
     * column, so a reported position is always a position in the source as the
     * user wrote it.
     */
    static List<SourceStatement> split(String source) {
        String lexable = blankMetaCommandLines(source);
        int[] lineStarts = lineStarts(lexable);

        List<SourceStatement> statements = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        int start = -1;

        CCJSqlParserTokenManager tokenManager = tokenManager(lexable, 1, 1);

        try {
            while (true) {
                Token token = tokenManager.getNextToken();

                if (token.kind == CCJSqlParserConstants.EOF) {
                    break;
                }

                int tokenStart = offset(lineStarts, token.beginLine, token.beginColumn);

                if (token.kind == CCJSqlParserConstants.ST_SEMICOLON) {
                    if (start >= 0) {
                        statements.add(
                            statement(
                                lexable,
                                lineStarts,
                                start,
                                tokenStart + token.image.length(),
                                tokens
                            )
                        );

                        tokens = new ArrayList<>();
                        start = -1;
                    }

                    continue;
                }

                if (start < 0) {
                    start = tokenStart;
                }

                tokens.add(token.image);

                String tag = dollarQuoteTag(token.image);

                if (tag != null) {
                    int bodyEnd = bodyEnd(lexable, tokenStart + tag.length(), tag, token);

                    tokenManager = tokenManager(
                        lexable.substring(bodyEnd),
                        lineOf(lineStarts, bodyEnd),
                        columnOf(lineStarts, bodyEnd)
                    );
                }
            }
        } catch (TokenMgrException e) {
            throw new SchemaParseException(SqlParseReason.of(e, true), e);
        }

        if (start >= 0) {
            statements.add(statement(lexable, lineStarts, start, lexable.length(), tokens));
        }

        return statements;
    }

    /**
     * A token manager over {@code text}, which begins at {@code line} and
     * {@code column} of the whole source, so every token it reports carries a
     * position in that source.
     */
    private static CCJSqlParserTokenManager tokenManager(String text, int line, int column) {
        return new CCJSqlParserTokenManager(
            new SimpleCharStream(new StringProvider(text), line, column)
        );
    }

    private static SourceStatement statement(
        String source,
        int[] lineStarts,
        int start,
        int end,
        List<String> tokens
    ) {
        return new SourceStatement(
            source.substring(start, end),
            lineOf(lineStarts, start),
            columnOf(lineStarts, start),
            List.copyOf(tokens)
        );
    }

    /**
     * The opening delimiter of a tagged dollar-quoted string the token image
     * begins with, or {@code null}. The token manager reports such a delimiter
     * as an ordinary word, glued to neighboring text where there is no
     * whitespace between them, as in {@code $fn$BEGIN}.
     */
    private static String dollarQuoteTag(String image) {
        Matcher matcher = DOLLAR_QUOTE_TAG.matcher(image);

        return matcher.lookingAt()
            ? matcher.group()
            : null;
    }

    /**
     * The offset directly after the closing delimiter of a tagged
     * dollar-quoted string, which PostgreSQL finds at the next occurrence of
     * the opening delimiter. A body that is never closed fails the source at
     * the delimiter that opened it.
     */
    private static int bodyEnd(String source, int searchFrom, String tag, Token opening) {
        int closing = source.indexOf(tag, searchFrom);

        if (closing < 0) {
            throw new SchemaParseException(
                "Unterminated dollar-quoted string at line %d, column %d"
                    .formatted(opening.beginLine, opening.beginColumn),
                null
            );
        }

        return closing + tag.length();
    }

    /**
     * The source with every character of a psql meta-command line, a line whose
     * first non-blank character is a backslash, replaced by a space. Line breaks
     * are kept, so the blanked source has the lines and columns of the original.
     */
    private static String blankMetaCommandLines(String source) {
        char[] characters = source.toCharArray();
        int lineStart = 0;

        for (int i = 0; i <= characters.length; i++) {
            if (i == characters.length || characters[i] == '\n' || characters[i] == '\r') {
                blankMetaCommandLine(characters, lineStart, i);

                if (
                    i + 1 < characters.length
                        && characters[i] == '\r'
                        && characters[i + 1] == '\n'
                ) {
                    i++;
                }

                lineStart = i + 1;
            }
        }

        return new String(characters);
    }

    private static void blankMetaCommandLine(char[] characters, int from, int to) {
        for (int i = from; i < to; i++) {
            if (characters[i] == ' ' || characters[i] == '\t') {
                continue;
            }

            if (characters[i] != '\\') {
                return;
            }

            Arrays.fill(characters, from, to, ' ');

            return;
        }
    }

    /**
     * The offset each line of the source begins at, counting {@code \n},
     * {@code \r\n}, and {@code \r} as one line break, as the token manager
     * counts them.
     */
    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();

        starts.add(0);

        for (int i = 0; i < source.length(); i++) {
            char character = source.charAt(i);

            if (character != '\n' && character != '\r') {
                continue;
            }

            if (character == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                i++;
            }

            starts.add(i + 1);
        }

        int[] lineStarts = new int[starts.size()];

        for (int i = 0; i < starts.size(); i++) {
            lineStarts[i] = starts.get(i);
        }

        return lineStarts;
    }

    /**
     * The offset of one line and column. The token manager counts a tab as one
     * column, so a column is the position of a character within its line.
     */
    private static int offset(int[] lineStarts, int line, int column) {
        return lineStarts[line - 1] + column - 1;
    }

    private static int lineOf(int[] lineStarts, int offset) {
        return lineIndexOf(lineStarts, offset) + 1;
    }

    private static int columnOf(int[] lineStarts, int offset) {
        return offset - lineStarts[lineIndexOf(lineStarts, offset)] + 1;
    }

    private static int lineIndexOf(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);

        return index >= 0
            ? index
            : -index - 2;
    }
}
