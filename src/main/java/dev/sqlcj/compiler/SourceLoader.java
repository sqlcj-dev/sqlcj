package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;

public interface SourceLoader {

    Source load(Config config);
}
