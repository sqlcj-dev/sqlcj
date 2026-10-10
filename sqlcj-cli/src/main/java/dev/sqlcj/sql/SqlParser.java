package dev.sqlcj.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class SqlParser {

    private final SqlParameterCompiler parameterCompiler = new SqlParameterCompiler();

    /**
     * Parses one SQL source into its statement, its compiled positional
     * parameters, and its placeholder occurrences in textual order.
     *
     * <p>The parser used for the returned statement is captured while parsing.
     * {@link CCJSqlParserUtil} hands a newly created parser to the consumer
     * again when it retries a source with complex parsing, so the last captured
     * parser owns the syntax tree of the returned statement.
     */
    public ParsedSql parse(String sql) {
        AtomicReference<CCJSqlParser> parser = new AtomicReference<>();

        Statement statement;

        try {
            statement = CCJSqlParserUtil.parse(sql, parser::set);
        } catch (JSQLParserException e) {
            throw new SqlParseException(SqlParseReason.of(e, false), e);
        }

        if (statement == null || parser.get() == null) {
            throw new SqlParseException("SQL source contains no statement.");
        }

        List<PlaceholderOccurrence> placeholders = new ArrayList<>();

        SqlParameters parameters = parameterCompiler.compile(
            sql,
            parser.get().getASTRoot(),
            placeholders
        );

        return new ParsedSql(statement, parameters, placeholders);
    }
}
