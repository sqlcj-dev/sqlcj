package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;

import java.util.List;

public interface SourceLoader {

    List<Source> load(Config config);
}
