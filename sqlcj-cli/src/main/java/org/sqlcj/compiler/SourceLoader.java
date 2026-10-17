package org.sqlcj.compiler;

import org.sqlcj.config.Config;

import java.util.List;

public interface SourceLoader {

    List<Source> load(Config config);
}
