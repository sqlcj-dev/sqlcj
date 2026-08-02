package dev.sqlcj.io;

import java.io.IOException;
import java.nio.file.Path;

public interface FileLoader {

    String read(Path path) throws IOException;
}
