package dev.sqlcj.config;

import javax.lang.model.SourceVersion;
import java.nio.file.Path;
import java.util.List;

/**
 * Validates the structure and values of a loaded configuration against the
 * supported version {@code "1"} contract.
 */
final class ConfigValidator {

    void validate(Config config, Path configFile) {
        validateVersion(config.version(), configFile);
        validateSql(config.sql(), configFile);
        validateJava(config.java(), configFile);
    }

    private void validateVersion(String version, Path configFile) {
        requireValue(version, "version", configFile);

        if (!Config.VERSION_1.equals(version)) {
            throw invalid(
                configFile,
                "unsupported 'version' value '%s', expected '%s'"
                    .formatted(version, Config.VERSION_1)
            );
        }
    }

    private void validateSql(List<SqlConfig> sql, Path configFile) {
        if (sql == null) {
            throw invalid(configFile, "'sql' is required");
        }

        if (sql.isEmpty()) {
            throw invalid(configFile, "'sql' must contain at least one entry");
        }

        for (int i = 0; i < sql.size(); i++) {
            SqlConfig entry = sql.get(i);

            if (entry == null) {
                throw invalid(configFile, "'sql[%d]' must not be null".formatted(i));
            }

            requireValue(entry.schema(), "sql[%d].schema".formatted(i), configFile);
            requireValue(entry.queries(), "sql[%d].queries".formatted(i), configFile);
        }
    }

    private void validateJava(JavaConfig java, Path configFile) {
        if (java == null) {
            throw invalid(configFile, "'java' is required");
        }

        requireValue(java.out(), "java.out", configFile);
        requireValue(java.packageName(), "java.package", configFile);

        if (!SourceVersion.isName(java.packageName())) {
            throw invalid(
                configFile,
                "'java.package' value '%s' is not a valid Java package name"
                    .formatted(java.packageName())
            );
        }
    }

    private void requireValue(String value, String field, Path configFile) {
        if (value == null) {
            throw invalid(configFile, "'%s' is required".formatted(field));
        }

        if (value.isBlank()) {
            throw invalid(configFile, "'%s' must not be blank".formatted(field));
        }
    }

    private ConfigurationException invalid(Path configFile, String detail) {
        return new ConfigurationException(
            "Invalid configuration in %s: %s".formatted(configFile, detail)
        );
    }
}
