package dev.sqlcj.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JavaConfig(
        String out,
        @JsonProperty("package")
        String packageName
) {
}
