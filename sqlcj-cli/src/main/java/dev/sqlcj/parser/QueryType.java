package dev.sqlcj.parser;

public enum QueryType {
    ONE,
    OPTIONAL,
    MANY,
    EXEC,
    EXEC_RESULT,
    BATCH_EXEC,
    BATCH_MANY,
    BATCH_ONE;

    public static QueryType from(String value) {
        return switch (value) {
            case ":one" -> ONE;
            case ":optional" -> OPTIONAL;
            case ":many" -> MANY;
            case ":exec" -> EXEC;
            case ":execresult" -> EXEC_RESULT;
            case ":batchexec" -> BATCH_EXEC;
            case ":batchmany" -> BATCH_MANY;
            case ":batchone" -> BATCH_ONE;
            default -> throw new IllegalArgumentException("Unknown query type: " + value);
        };
    }
}
