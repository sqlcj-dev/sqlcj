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
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SqlcjCompiler {

    private final SourceLoader sourceLoader = new DefaultSourceLoader();
    private final SqlParser sqlParser = new SqlParser();
    private final QueryAnalyzer queryAnalyzer = new QueryAnalyzer();
    private final GeneratedFileWriter generatedFileWriter = new GeneratedFileWriter();
    private final SchemaParser schemaParser = new DefaultSchemaParser();

    public void compile(Config config) {
        List<Source> sources = sourceLoader.load(config);

        CodeGenerator codeGenerator =
                new JavaCodeGenerator(config.java().packageName());

        Path outputDirectory = Path.of(config.java().out());

        Set<Path> generatedPaths = new HashSet<>();

        for (Source source : sources) {
            Schema schema = schemaParser.parse(source.schema());

            for (Query query : source.queries()) {
                compileQuery(
                        query,
                        schema,
                        codeGenerator,
                        outputDirectory,
                        generatedPaths
                );
            }
        }
    }

    private void compileQuery(
            Query query,
            Schema schema,
            CodeGenerator codeGenerator,
            Path outputDirectory,
            Set<Path> generatedPaths
    ) {
        Statement statement = sqlParser.parse(query.sql());
        QueryModel model = queryAnalyzer.analyze(query, statement, schema);
        GeneratedFile file = codeGenerator.generate(model);

        if (!generatedPaths.add(file.path())) {
            throw new CompilationException(
                    "Duplicate generated file: " + file.path()
            );
        }

        write(file, outputDirectory);
    }

    private void write(
            GeneratedFile file,
            Path outputDirectory
    ) {
        try {
            generatedFileWriter.write(file, outputDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
