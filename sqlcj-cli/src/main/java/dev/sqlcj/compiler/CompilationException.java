package dev.sqlcj.compiler;

/**
 * Signals that configured SQL sources cannot be loaded or compiled, or that
 * their generated output cannot be written.
 */
public class CompilationException extends RuntimeException {

    public CompilationException(String message) {
        super(message);
    }

    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
