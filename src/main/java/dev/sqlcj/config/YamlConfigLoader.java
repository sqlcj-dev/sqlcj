package dev.sqlcj.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Path;

public class YamlConfigLoader implements ConfigLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    @Override
    public Config load(Path path) {
        return OBJECT_MAPPER.readValue(path.toFile(), Config.class);
    }
}
