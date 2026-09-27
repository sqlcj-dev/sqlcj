package dev.sqlcj.compiler;

import java.nio.file.Path;

/**
 * One loaded schema file and the path it was loaded from, which identifies it
 * in a compilation diagnostic.
 */
public record SchemaFile(
    Path path,
    String sql
) {
}
