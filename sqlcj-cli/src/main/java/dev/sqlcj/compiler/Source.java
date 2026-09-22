package dev.sqlcj.compiler;

import dev.sqlcj.parser.Query;

import java.nio.file.Path;
import java.util.List;

/**
 * One loaded configuration entry and the paths it was loaded from, which
 * identify a source in a compilation diagnostic.
 */
public record Source(
    Path schemaPath,
    String schema,
    Path queriesPath,
    List<Query> queries
) {
}
