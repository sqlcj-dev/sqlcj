package dev.sqlcj.compiler;

import dev.sqlcj.analysis.QueryAnalyzer;
import dev.sqlcj.analysis.QueryGroupModel;
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
import dev.sqlcj.sql.ParsedSql;
import dev.sqlcj.sql.SqlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * Compiles every configured entry before any generated file is written, so
     * a failure in a later source cannot leave a mixture of previously
     * generated and newly generated output.
     */
    public void compile(Config config) {
        List<Source> sources = sourceLoader.load(config);

        CodeGenerator codeGenerator = new JavaCodeGenerator(config.java().packageName());

        List<GeneratedFile> files = generate(sources, codeGenerator);

        Path outputDirectory = Path.of(config.java().out());

        for (GeneratedFile file : files) {
            write(file, outputDirectory);
        }
    }

    /** Generates one repository per configured query group. */
    private List<GeneratedFile> generate(List<Source> sources, CodeGenerator codeGenerator) {
        List<GeneratedFile> files = new ArrayList<>();
        Map<String, GeneratedRepository> generatedRepositories = new HashMap<>();

        for (Source source : sources) {
            Schema schema = parseSchema(source);

            GeneratedFile file = generateRepository(
                source,
                analyze(source, schema),
                codeGenerator
            );

            checkGeneratedPath(source, file, generatedRepositories);

            files.add(file);
        }

        return files;
    }

    private Schema parseSchema(Source source) {
        try {
            return schemaParser.parse(source.schema());
        } catch (RuntimeException e) {
            throw new CompilationException(
                "Invalid schema source %s: %s".formatted(source.schemaPath(), reason(e)),
                e
            );
        }
    }

    /** Analyzes every query of one group against that group's schema. */
    private QueryGroupModel analyze(Source source, Schema schema) {
        List<QueryModel> queries = new ArrayList<>(source.queries().size());

        for (Query query : source.queries()) {
            queries.add(analyzeQuery(source, query, schema));
        }

        return new QueryGroupModel(source.name(), List.copyOf(queries));
    }

    /**
     * Analyzes one query, reporting a parse or analysis failure with the
     * source, query, and header line it belongs to.
     */
    private QueryModel analyzeQuery(Source source, Query query, Schema schema) {
        try {
            ParsedSql parsedSql = sqlParser.parse(query.sql());

            return queryAnalyzer.analyze(query, parsedSql, schema);
        } catch (RuntimeException e) {
            throw new CompilationException(
                "Invalid query '%s' in %s at line %d: %s"
                    .formatted(
                        query.name(),
                        source.queriesPath(),
                        query.line(),
                        reason(e)
                    ),
                e
            );
        }
    }

    /**
     * Generates one group, reporting a generation failure such as a repeated
     * repository method with the group and its query source.
     */
    private GeneratedFile generateRepository(
        Source source,
        QueryGroupModel group,
        CodeGenerator codeGenerator
    ) {
        try {
            return codeGenerator.generate(group);
        } catch (RuntimeException e) {
            throw new CompilationException(
                "Invalid query group '%s' in %s: %s"
                    .formatted(group.name(), source.queriesPath(), reason(e)),
                e
            );
        }
    }

    /** Uses the first message line so a diagnostic stays focused. */
    private String reason(RuntimeException e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return message.lines()
            .findFirst()
            .orElse(message)
            .trim();
    }

    /**
     * Rejects a generated path that repeats, or differs only by case from, an
     * already generated path before any file is written.
     */
    private void checkGeneratedPath(
        Source source,
        GeneratedFile file,
        Map<String, GeneratedRepository> generatedRepositories
    ) {
        String portabilityKey = file.path()
            .toString()
            .toLowerCase(Locale.ROOT);

        GeneratedRepository generated = generatedRepositories.get(portabilityKey);

        if (generated == null) {
            generatedRepositories.put(
                portabilityKey,
                new GeneratedRepository(source.name(), file.path())
            );

            return;
        }

        if (generated.path().equals(file.path())) {
            throw new CompilationException(
                "Duplicate generated file for repositories '%s' and '%s': %s"
                    .formatted(generated.groupName(), source.name(), file.path())
            );
        }

        throw new CompilationException(
            "Generated file paths for repositories '%s' and '%s' differ only by case: %s and %s"
                .formatted(
                    generated.groupName(),
                    source.name(),
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

    private record GeneratedRepository(String groupName, Path path) {
    }
}
