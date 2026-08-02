package dev.sqlcj.config;

import java.nio.file.Path;

public interface ConfigLoader {

    Config load(Path path);
}
