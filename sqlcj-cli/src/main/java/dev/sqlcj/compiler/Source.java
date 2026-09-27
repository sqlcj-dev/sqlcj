package dev.sqlcj.compiler;

import dev.sqlcj.parser.Query;

import java.nio.file.Path;
import java.util.List;

/**
 * One loaded configuration entry and the paths it was loaded from, which
 * identify a source in a compilation diagnostic.
 *
 * @param name the configured group identity that names the generated repository
 * @param schemaFiles the schema files of the entry, in the order they apply
 */
public record Source(
    String name,
    List<SchemaFile> schemaFiles,
    Path queriesPath,
    List<Query> queries
) {
}
