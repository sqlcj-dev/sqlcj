package dev.sqlcj.config;

import java.util.List;

public record Config(
        List<SqlConfig> sql,
        JavaConfig java
) {
}
