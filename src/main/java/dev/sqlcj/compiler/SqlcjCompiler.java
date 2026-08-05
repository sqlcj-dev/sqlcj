package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.parser.Query;
import dev.sqlcj.sql.SqlParser;
import net.sf.jsqlparser.statement.Statement;

public final class SqlcjCompiler {

    private final SourceLoader sourceLoader = new DefaultSourceLoader();
    private final SqlParser sqlParser = new SqlParser();

    public void compile(Config config) {
        Source source = sourceLoader.load(config);

        for (Query query : source.queries()) {
            compileQuery(query);
        }
    }

    private void compileQuery(Query query) {
        Statement statement = sqlParser.parse(query.sql());
    }
}
