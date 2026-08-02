package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.io.DefaultFileLoader;
import dev.sqlcj.io.FileLoader;
import dev.sqlcj.parser.DefaultQueryParser;
import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

public final class DefaultSourceLoader implements SourceLoader {

    private final FileLoader fileLoader = new DefaultFileLoader();
    private final QueryParser queryParser = new DefaultQueryParser();

    @Override
    public Source load(Config config) {
        SqlConfig sqlConfig = config.sql().getFirst();
        String schema = read(Path.of(sqlConfig.schema()));
        String querySource = read(Path.of(sqlConfig.queries()));
        List<Query> queries = queryParser.parse(querySource);
        return new Source(schema, queries);
    }

    private String read(Path path) {
        try {
            return fileLoader.read(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
