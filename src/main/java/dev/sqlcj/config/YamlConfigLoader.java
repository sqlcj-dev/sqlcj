package dev.sqlcj.config;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class YamlConfigLoader implements ConfigLoader {

    private static final ObjectMapper OBJECT_MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .withCoercionConfig(
                    LogicalType.Textual,
                    coercion -> {
                        coercion.setCoercion(
                                CoercionInputShape.Integer,
                                CoercionAction.Fail
                        );
                        coercion.setCoercion(
                                CoercionInputShape.Float,
                                CoercionAction.Fail
                        );
                        coercion.setCoercion(
                                CoercionInputShape.Boolean,
                                CoercionAction.Fail
                        );
                    }
            )
            .build();

    private final ConfigValidator validator = new ConfigValidator();

    @Override
    public Config load(Path path) {
        Path configFile = path.toAbsolutePath().normalize();

        Config config = read(configFile);

        validator.validate(config, configFile);

        return resolvePaths(config, configFile.getParent());
    }

    private Config read(Path configFile) {
        if (!Files.isReadable(configFile)) {
            throw new ConfigurationException(
                    "Cannot read configuration file: " + configFile
            );
        }

        Config config;

        try {
            config = OBJECT_MAPPER.readValue(configFile.toFile(), Config.class);
        } catch (UnrecognizedPropertyException e) {
            throw new ConfigurationException(
                    "Invalid configuration in %s: unknown field '%s'"
                            .formatted(configFile, e.getPropertyName()),
                    e
            );
        } catch (MismatchedInputException e) {
            throw invalidValue(configFile, e);
        } catch (JacksonException e) {
            throw new ConfigurationException(
                    "Malformed configuration file: " + configFile,
                    e
            );
        }

        if (config == null) {
            throw new ConfigurationException(
                    "Configuration file is empty: " + configFile
            );
        }

        return config;
    }

    /**
     * Translates a Jackson mapping failure into a configuration diagnostic
     * naming the configuration file, the offending property path, and the
     * offending value or type as far as Jackson reports them.
     */
    private ConfigurationException invalidValue(
            Path configFile,
            MismatchedInputException e
    ) {
        StringBuilder detail = new StringBuilder("invalid ");

        String property = propertyPath(e);

        if (property != null) {
            detail.append("'").append(property).append("' ");
        }

        detail.append("value");

        if (e instanceof InvalidFormatException format
                && format.getValue() != null) {
            detail.append(" '").append(format.getValue()).append("'");
        }

        String actualType = describeInputShape(e.getCurrentToken());

        if (actualType != null) {
            detail.append(" of type ").append(actualType);
        }

        String expectedType = describeExpectedType(e.getTargetType());

        if (expectedType != null) {
            detail.append(", expected ").append(expectedType);
        }

        return new ConfigurationException(
                "Invalid configuration in %s: %s".formatted(configFile, detail),
                e
        );
    }

    private String propertyPath(JacksonException e) {
        StringBuilder path = new StringBuilder();

        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append(".");
                }

                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append("[").append(reference.getIndex()).append("]");
            }
        }

        return path.isEmpty() ? null : path.toString();
    }

    private String describeInputShape(JsonToken token) {
        if (token == null) {
            return null;
        }

        return switch (token) {
            case VALUE_STRING -> "string";
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "number";
            case VALUE_TRUE, VALUE_FALSE -> "boolean";
            case START_ARRAY -> "list";
            case START_OBJECT -> "mapping";
            case VALUE_NULL -> "null";
            default -> null;
        };
    }

    private String describeExpectedType(Class<?> targetType) {
        if (targetType == null) {
            return null;
        }

        if (targetType == String.class) {
            return "a string";
        }

        if (Collection.class.isAssignableFrom(targetType)) {
            return "a list";
        }

        if (targetType.isRecord() || Map.class.isAssignableFrom(targetType)) {
            return "a mapping";
        }

        return "a value of type " + targetType.getSimpleName();
    }

    private Config resolvePaths(
            Config config,
            Path baseDirectory
    ) {
        List<SqlConfig> sql = config.sql().stream()
                .map(entry -> new SqlConfig(
                        resolvePath(entry.schema(), baseDirectory),
                        resolvePath(entry.queries(), baseDirectory)
                ))
                .toList();

        JavaConfig java = new JavaConfig(
                resolvePath(config.java().out(), baseDirectory),
                config.java().packageName()
        );

        return new Config(config.version(), sql, java);
    }

    private String resolvePath(
            String value,
            Path baseDirectory
    ) {
        Path path = Path.of(value);

        if (path.isAbsolute()) {
            return path.normalize().toString();
        }

        return baseDirectory.resolve(path).normalize().toString();
    }
}
