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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SqlcjCompiler {

    private final SourceLoader sourceLoader = new DefaultSourceLoader();
    private final SqlParser sqlParser = new SqlParser();
    private final QueryAnalyzer queryAnalyzer = new QueryAnalyzer();
    private final GeneratedFileWriter generatedFileWriter = new GeneratedFileWriter();
    private final SchemaParser schemaParser = new DefaultSchemaParser();

    public void compile(Config config) {
        List<Source> sources = sourceLoader.load(config);

        CodeGenerator codeGenerator = new JavaCodeGenerator(config.java().packageName());

        Path outputDirectory = Path.of(config.java().out());

        Map<String, GeneratedQuery> generatedQueries = new HashMap<>();

        for (Source source : sources) {
            Schema schema = schemaParser.parse(source.schema());

            for (Query query : source.queries()) {
                compileQuery(
                    query,
                    schema,
                    codeGenerator,
                    outputDirectory,
                    generatedQueries
                );
            }
        }
    }

    private void compileQuery(
        Query query,
        Schema schema,
        CodeGenerator codeGenerator,
        Path outputDirectory,
        Map<String, GeneratedQuery> generatedQueries
    ) {
        Statement statement = sqlParser.parse(query.sql());
        QueryModel model = queryAnalyzer.analyze(query, statement, schema);
        GeneratedFile file = codeGenerator.generate(model);

        checkGeneratedPath(query, file, generatedQueries);

        write(file, outputDirectory);
    }

    /**
     * Rejects a generated path that repeats, or differs only by case from, an
     * already generated path before the earlier file can be overwritten.
     */
    private void checkGeneratedPath(
        Query query,
        GeneratedFile file,
        Map<String, GeneratedQuery> generatedQueries
    ) {
        String portabilityKey = file.path()
            .toString()
            .toLowerCase(Locale.ROOT);

        GeneratedQuery generated = generatedQueries.get(portabilityKey);

        if (generated == null) {
            generatedQueries.put(
                portabilityKey,
                new GeneratedQuery(query.name(), file.path())
            );

            return;
        }

        if (generated.path().equals(file.path())) {
            throw new CompilationException(
                "Duplicate generated file for queries '%s' and '%s': %s"
                    .formatted(generated.queryName(), query.name(), file.path())
            );
        }

        throw new CompilationException(
            "Generated file paths for queries '%s' and '%s' differ only by case: %s and %s"
                .formatted(
                    generated.queryName(),
                    query.name(),
                    generated.path(),
                    file.path()
                )
        );
    }

    private void write(GeneratedFile file, Path outputDirectory) {
        try {
            generatedFileWriter.write(file, outputDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record GeneratedQuery(String queryName, Path path) {
    }
}
