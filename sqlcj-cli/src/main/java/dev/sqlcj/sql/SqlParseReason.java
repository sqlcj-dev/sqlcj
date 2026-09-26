package dev.sqlcj.sql;

import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;

/**
 * The corrective fact of a SQL syntax failure, such as {@code Encountered
 * unexpected token: ";" <ST_SEMICOLON>}.
 *
 * <p>The SQL parser reports a syntax failure through a chain of wrapping
 * exceptions whose outer messages repeat the class name of the failure they
 * wrap, so the fact is taken from the {@link ParseException} that chain
 * carries, or from its innermost cause when it carries none, and never names an
 * exception class.
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

        String reason = firstLine(parseFailure);

        Token token = parseFailure.currentToken == null
            ? null
            : parseFailure.currentToken.next;

        if (!withLocation || token == null) {
            return reason;
        }

        return "%s at line %d, column %d"
            .formatted(reason, token.beginLine, token.beginColumn);
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
