package org.sqlcj.compiler;

import org.sqlcj.analysis.QueryAnalyzer;
import org.sqlcj.analysis.QueryGroupModel;
import org.sqlcj.analysis.QueryModel;
import org.sqlcj.config.Config;
import org.sqlcj.config.ConfigurationException;
import org.sqlcj.generator.CodeGenerator;
import org.sqlcj.generator.GeneratedFile;
import org.sqlcj.generator.JavaCodeGenerator;
import org.sqlcj.io.GeneratedFileWriter;
import org.sqlcj.io.GeneratedOutputManifest;
import org.sqlcj.parser.Query;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.parser.DefaultSchemaParser;
import org.sqlcj.schema.parser.SchemaParseException;
import org.sqlcj.schema.parser.SchemaParser;
import org.sqlcj.sql.ParsedSql;
import org.sqlcj.sql.SqlParseException;
import org.sqlcj.sql.SqlParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class SqlcjCompiler {

    /**
     * The end of every internal-error diagnostic, which reports a failure sqlcj
     * itself did not foresee and therefore describes no input.
     */
    private static final String INTERNAL_ERROR_SUFFIX = "; please report this sqlcj defect";

    private final SourceLoader sourceLoader = new DefaultSourceLoader();
    private final GeneratedFileWriter generatedFileWriter = new GeneratedFileWriter();
    private final GeneratedOutputManifest generatedOutputManifest = new GeneratedOutputManifest();

    private final SchemaParser schemaParser;

    /** Parses and analyzes one query of one group against that group's schema. */
    private final BiFunction<Query, Schema, QueryModel> queryAnalysis;

    /** The generator of one configured package, by that package's name. */
    private final Function<String, CodeGenerator> codeGenerators;

    public SqlcjCompiler() {
        this(
            new DefaultSchemaParser(),
            defaultQueryAnalysis(),
            JavaCodeGenerator::new
        );
    }

    SqlcjCompiler(
        SchemaParser schemaParser,
        BiFunction<Query, Schema, QueryModel> queryAnalysis,
        Function<String, CodeGenerator> codeGenerators
    ) {
        this.schemaParser = schemaParser;
        this.queryAnalysis = queryAnalysis;
        this.codeGenerators = codeGenerators;
    }

    /** Parses one query's SQL source and analyzes it against its schema. */
    private static BiFunction<Query, Schema, QueryModel> defaultQueryAnalysis() {
        SqlParser sqlParser = new SqlParser();
        QueryAnalyzer queryAnalyzer = new QueryAnalyzer();

        return (query, schema) -> {
            ParsedSql parsedSql = sqlParser.parse(query.sql());

            return queryAnalyzer.analyze(query, parsedSql, schema);
        };
    }

    /**
     * Compiles every configured entry before any generated file is written, so
     * a failure in a later source cannot leave a mixture of previously
     * generated and newly generated output.
     *
     * <p>The manifest of the previous run is read before the first write, and
     * the files it lists that this run did not produce are deleted before the
     * new manifest replaces it, so a failed write or cleanup keeps the previous
     * manifest for the next run.
     */
    public void compile(Config config) {
        List<Source> sources = sourceLoader.load(config);

        String packageName = config.java().packageName();

        CodeGenerator codeGenerator = codeGenerators.apply(packageName);

        List<GeneratedFile> files = generate(sources, codeGenerator, packageName);

        Path outputDirectory = Path.of(config.java().out());

        List<String> previousEntries = readManifest(outputDirectory);

        for (GeneratedFile file : files) {
            write(file, outputDirectory);
        }

        List<Path> generatedPaths = files.stream()
            .map(GeneratedFile::path)
            .toList();

        deleteStale(outputDirectory, previousEntries, generatedPaths);

        writeManifest(outputDirectory, generatedPaths);
    }

    /**
     * Generates one repository per configured query group, followed by the row
     * records and the enum types those groups share.
     */
    private List<GeneratedFile> generate(
        List<Source> sources,
        CodeGenerator codeGenerator,
        String packageName
    ) {
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

        files.addAll(generateShared(codeGenerator, packageName));

        return files;
    }

    /**
     * Generates the row records and the enum types the groups of one package
     * share, which belong to the package rather than to one group, so their own
     * diagnostics name the package and not a group.
     */
    private List<GeneratedFile> generateShared(CodeGenerator codeGenerator, String packageName) {
        try {
            List<GeneratedFile> shared = new ArrayList<>(codeGenerator.generateRows());

            shared.addAll(codeGenerator.generateEnums());

            return shared;
        } catch (RuntimeException e) {
            if (isDiagnostic(e)) {
                throw e;
            }

            throw internalError(
                "Internal error while generating the shared row records and enums of package '%s'"
                    .formatted(packageName),
                e
            );
        }
    }

    /**
     * Applies the schema files of one entry in order to that entry's schema, so
     * a table keeps the position of the file that declares it and a later file
     * alters what the earlier files left.
     */
    private Schema parseSchema(Source source) {
        Schema schema = new Schema(List.of());

        for (SchemaFile schemaFile : source.schemaFiles()) {
            schema = parseSchemaFile(schema, schemaFile);
        }

        return schema;
    }

    private Schema parseSchemaFile(Schema schema, SchemaFile schemaFile) {
        try {
            return schemaParser.parse(schema, schemaFile.sql());
        } catch (RuntimeException e) {
            if (!isDiagnostic(e)) {
                throw internalError(
                    "Internal error while parsing schema source %s".formatted(schemaFile.path()),
                    e
                );
            }

            throw new CompilationException(
                "Invalid schema source %s: %s".formatted(schemaFile.path(), reason(e)),
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

        return new QueryGroupModel(source.name(), List.copyOf(queries), schema.enums());
    }

    /**
     * Analyzes one query, reporting a parse or analysis failure with the
     * source, query, and header line it belongs to.
     */
    private QueryModel analyzeQuery(Source source, Query query, Schema schema) {
        try {
            return queryAnalysis.apply(query, schema);
        } catch (RuntimeException e) {
            if (!isDiagnostic(e)) {
                throw internalError(
                    "Internal error while analyzing query '%s' in %s at line %d"
                        .formatted(query.name(), source.queriesPath(), query.line()),
                    e
                );
            }

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
     * repository method, or a row record that conflicts with the row of an
     * earlier group, with the group and its query source.
     */
    private GeneratedFile generateRepository(
        Source source,
        QueryGroupModel group,
        CodeGenerator codeGenerator
    ) {
        try {
            return codeGenerator.generate(group);
        } catch (RuntimeException e) {
            if (!isDiagnostic(e)) {
                throw internalError(
                    "Internal error while generating query group '%s' in %s"
                        .formatted(group.name(), source.queriesPath()),
                    e
                );
            }

            throw new CompilationException(
                "Invalid query group '%s' in %s: %s"
                    .formatted(group.name(), source.queriesPath(), reason(e)),
                e
            );
        }
    }

    /**
     * Reports whether a failure of one compilation step is a diagnostic of a
     * compiled input, which sqlcj states in SQL terms.
     *
     * <p>The schema parser, the SQL parser, the configuration, and the compiler
     * itself have exceptions of their own, and the schema parser, the analyzer,
     * and the generator state a diagnostic by convention as an unsupported
     * operation or an illegal argument that carries a message. Every other
     * exception, including a subclass such as {@link NumberFormatException} and
     * an exception of that convention's types without a message, is a failure
     * sqlcj did not foresee.
     */
    private boolean isDiagnostic(RuntimeException e) {
        if (
            e instanceof SqlParseException
                || e instanceof SchemaParseException
                || e instanceof ConfigurationException
                || e instanceof CompilationException
        ) {
            return true;
        }

        boolean convention = e.getClass() == UnsupportedOperationException.class
            || e.getClass() == IllegalArgumentException.class;

        return convention && e.getMessage() != null && !e.getMessage().isBlank();
    }

    /**
     * Reports an unexpected failure of one compilation step as one line that
     * names the step's own input and asks for a report, and carries neither the
     * message nor the class of the failure, which describes no compiled input.
     * The failure itself stays as the cause.
     */
    private CompilationException internalError(String step, RuntimeException e) {
        return new CompilationException(step + INTERNAL_ERROR_SUFFIX, e);
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
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private List<String> readManifest(Path outputDirectory) {
        try {
            return generatedOutputManifest.read(outputDirectory);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private void deleteStale(
        Path outputDirectory,
        List<String> previousEntries,
        List<Path> generatedPaths
    ) {
        try {
            generatedOutputManifest.deleteStale(outputDirectory, previousEntries, generatedPaths);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private void writeManifest(Path outputDirectory, List<Path> generatedPaths) {
        try {
            generatedOutputManifest.write(outputDirectory, generatedPaths);
        } catch (IOException e) {
            throw new CompilationException(e.getMessage(), e);
        }
    }

    private record GeneratedRepository(String groupName, Path path) {
    }
}
