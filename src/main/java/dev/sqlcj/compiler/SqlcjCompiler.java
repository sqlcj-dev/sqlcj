package dev.sqlcj.compiler;

import dev.sqlcj.analysis.QueryAnalyzer;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.config.Config;
import dev.sqlcj.generator.CodeGenerator;
import dev.sqlcj.generator.GeneratedFile;
import dev.sqlcj.generator.JavaCodeGenerator;
import dev.sqlcj.io.GeneratedFileWriter;
import dev.sqlcj.parser.Query;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.parser.DefaultSchemaParser;
import dev.sqlcj.schema.parser.SchemaParser;
import dev.sqlcj.sql.SqlParser;
import net.sf.jsqlparser.statement.Statement;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class SqlcjCompiler {

    private final SourceLoader sourceLoader = new DefaultSourceLoader();
    private final SqlParser sqlParser = new SqlParser();
    private final QueryAnalyzer queryAnalyzer = new QueryAnalyzer();
    private final CodeGenerator codeGenerator = new JavaCodeGenerator();
    private final GeneratedFileWriter generatedFileWriter = new GeneratedFileWriter();
    private final SchemaParser schemaParser = new DefaultSchemaParser();

    public void compile(Config config) {
        Source source = sourceLoader.load(config);
        Schema schema = schemaParser.parse(source.schema());

        for (Query query : source.queries()) {
            compileQuery(query);
        }
    }

    private void compileQuery(Query query) {
        Statement statement = sqlParser.parse(query.sql());
        QueryModel model = queryAnalyzer.analyze(query, statement);
        GeneratedFile file = codeGenerator.generate(model);
        write(file);
    }

    private void write(GeneratedFile file) {
        try {
            generatedFileWriter.write(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
