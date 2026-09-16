package dev.sqlcj.config;

import java.util.List;

public record Config(
    String version,
    List<SqlConfig> sql,
    JavaConfig java
) {

    public static final String VERSION_1 = "1";

    public Config(
        List<SqlConfig> sql,
        JavaConfig java
    ) {
        this(
            VERSION_1,
            sql,
            java
        );
    }
}
