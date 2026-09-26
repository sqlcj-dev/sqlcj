package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.io.DefaultFileLoader;
import dev.sqlcj.io.FileLoader;
import dev.sqlcj.io.FileSystemReason;
import dev.sqlcj.parser.DefaultQueryParser;
import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DefaultSourceLoader implements SourceLoader {

    private final FileLoader fileLoader = new DefaultFileLoader();
    private final QueryParser queryParser = new DefaultQueryParser();

    /**
     * Loads every configured entry as its own query group. A query name is
     * scoped to the group that declares it, so two groups may name the same
     * query.
     */
    @Override
    public List<Source> load(Config config) {
        List<Source> sources = new ArrayList<>();

        for (SqlConfig sqlConfig : config.sql()) {
            Path schemaPath = Path.of(sqlConfig.schema());
            Path queriesPath = Path.of(sqlConfig.queries());

            String schema = read(schemaPath, "schema");
            List<Query> queries = parse(read(queriesPath, "queries"), queriesPath);

            sources.add(
                new Source(
                    sqlConfig.name(),
                    schemaPath,
                    schema,
                    queriesPath,
                    queries
                )
            );
        }

        return sources;
    }

    private String read(Path path, String kind) {
        try {
            return fileLoader.read(path);
        } catch (IOException e) {
            throw new CompilationException(
                "Cannot read %s source: %s: %s"
                    .formatted(kind, path, FileSystemReason.of(e)),
                e
            );
        }
    }

    private List<Query> parse(String querySource, Path queriesPath) {
        try {
            return queryParser.parse(querySource);
        } catch (IllegalArgumentException e) {
            throw new CompilationException(
                "Invalid query source %s: %s".formatted(queriesPath, e.getMessage()),
                e
            );
        }
    }
}
