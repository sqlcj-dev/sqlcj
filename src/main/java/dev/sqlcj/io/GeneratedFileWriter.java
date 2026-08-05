package dev.sqlcj.io;

import dev.sqlcj.generator.GeneratedFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class GeneratedFileWriter {

    public void write(GeneratedFile file) throws IOException {
        Path parent = file.path().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                file.path(),
                file.content(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
