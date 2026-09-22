package dev.sqlcj.sql;

public class SqlParseException extends RuntimeException {

    public SqlParseException(String message) {
        super(message);
    }

    public SqlParseException(Throwable cause) {
        super(cause);
    }
}
