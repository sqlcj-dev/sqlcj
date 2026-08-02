package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.io.DefaultFileLoader;
import dev.sqlcj.io.FileLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class DefaultSourceLoader implements SourceLoader {

    private final FileLoader fileLoader = new DefaultFileLoader();

    @Override
    public Source load(Config config) {
        SqlConfig sqlConfig = config.sql().getFirst();
        String schema = read(Path.of(sqlConfig.schema()));
        return new Source(schema);
    }

    private String read(Path path) {
        try {
            return fileLoader.read(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
