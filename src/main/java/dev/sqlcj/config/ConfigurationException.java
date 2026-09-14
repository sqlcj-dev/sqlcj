package dev.sqlcj.config;

/**
 * Signals that a sqlcj configuration file cannot be read or does not satisfy
 * the supported configuration contract.
 */
public class ConfigurationException extends RuntimeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
