package dev.sqlcj.runtime;

/**
 * A text argument that carries no declared SQL type.
 *
 * <p>{@link JdbcQueryExecutor} binds {@link #value()} as text without a
 * declared SQL type, so the database types it from the context of the
 * placeholder it is bound to rather than from the Java type of the value. That
 * is how a value whose server type has no JDBC type of its own, such as
 * PostgreSQL's {@code json} and {@code jsonb}, is accepted where a value bound
 * as {@code varchar} would be rejected.
 *
 * <p>The value may be {@code null}, which is bound as an untyped SQL
 * {@code NULL}. The text itself is never inspected, parsed, or normalized.
 */
public record UntypedText(
    String value
) {
}
