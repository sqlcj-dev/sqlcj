package dev.sqlcj.io;

import dev.sqlcj.generator.GeneratedFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class GeneratedFileWriter {

    public void write(GeneratedFile file, Path outputDirectory) throws IOException {
        Path outputFile = outputDirectory.resolve(file.path());

        Path parent = outputFile.getParent();

        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IOException("Cannot create output directory: " + parent, e);
            }
        }

        try {
            Files.writeString(
                outputFile,
                file.content(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IOException("Cannot write generated file: " + outputFile, e);
        }
    }
}
