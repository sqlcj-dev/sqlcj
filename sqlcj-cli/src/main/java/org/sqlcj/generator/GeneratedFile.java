package org.sqlcj.generator;

import java.nio.file.Path;

public record GeneratedFile(
    Path path,
    String content
) {
}
