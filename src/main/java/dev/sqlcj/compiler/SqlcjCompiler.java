package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;

public final class SqlcjCompiler {

    private final SourceLoader sourceLoader = new DefaultSourceLoader();

    public void compile(Config config) {
        Source source = sourceLoader.load(config);
        System.out.printf("Loaded %d queries%n", source.queries().size());
    }
}
