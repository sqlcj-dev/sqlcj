package dev.sqlcj.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DefaultFileLoader implements FileLoader {

    @Override
    public String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
