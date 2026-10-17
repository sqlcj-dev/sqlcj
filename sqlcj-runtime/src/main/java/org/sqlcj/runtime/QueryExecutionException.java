package org.sqlcj.runtime;

public class QueryExecutionException extends RuntimeException {

    public QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
