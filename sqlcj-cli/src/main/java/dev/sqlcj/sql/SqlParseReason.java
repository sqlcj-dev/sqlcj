package dev.sqlcj.sql;

import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;

/**
 * The corrective fact of a SQL syntax failure, such as {@code Encountered
 * unexpected token: ";"}.
 *
 * <p>The SQL parser reports a syntax failure through a chain of wrapping
 * exceptions whose outer messages repeat the class name of the failure they
 * wrap, and whose {@link ParseException} states the unexpected token with
 * parser-internal token kinds and lexical states. The fact is therefore
 * composed from the unexpected token that chain carries, or taken from the
 * innermost cause when it carries no {@link ParseException}, and never names an
 * exception class or a parser internal.
 */
public final class SqlParseReason {

    private SqlParseReason() {
    }

    /**
     * The corrective fact of one SQL syntax failure, optionally followed by the
     * line and column of the unexpected token.
     *
     * <p>A caller that already names a location, such as the header line of the
     * failing query, asks for the reason without one.
     */
    public static String of(Throwable failure, boolean withLocation) {
        ParseException parseFailure = parseFailure(failure);

        if (parseFailure == null) {
            return firstLine(innermostCause(failure));
        }

        Token token = parseFailure.currentToken == null
            ? null
            : parseFailure.currentToken.next;

        if (token == null) {
            return firstLine(parseFailure);
        }

        String reason = unexpected(token);

        if (!withLocation) {
            return reason;
        }

        return "%s at line %d, column %d"
            .formatted(reason, token.beginLine, token.beginColumn);
    }

    /**
     * States the token the parser did not expect. The end of input has no image
     * to quote, and a token image that spans lines is quoted with its line
     * breaks escaped so the reason stays one line.
     */
    private static String unexpected(Token token) {
        if (token.kind == CCJSqlParserConstants.EOF) {
            return "Encountered unexpected end of input";
        }

        return "Encountered unexpected token: \"%s\"".formatted(
            token.image
                .replace("\r", "\\r")
                .replace("\n", "\\n")
        );
    }

    /** The parse failure the exception chain carries, or {@code null}. */
    private static ParseException parseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ParseException parseFailure) {
                return parseFailure;
            }
        }

        return null;
    }

    /**
     * The innermost cause of the exception chain, which states a lexical failure
     * in its own wording while every wrapper around it repeats its class name.
     */
    private static Throwable innermostCause(Throwable failure) {
        Throwable cause = failure;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        return cause;
    }

    private static String firstLine(Throwable failure) {
        String message = failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }

        return message.lines()
            .findFirst()
            .orElse(message)
            .trim();
    }
}
