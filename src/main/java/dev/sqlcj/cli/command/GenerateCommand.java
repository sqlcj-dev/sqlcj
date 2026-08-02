package dev.sqlcj.cli.command;

import dev.sqlcj.compiler.SqlcjCompiler;
import dev.sqlcj.config.Config;
import dev.sqlcj.config.ConfigLoader;
import dev.sqlcj.config.YamlConfigLoader;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "generate",
        description = "Generate Java sources from SQL queries"
)
public class GenerateCommand implements Runnable {

    private final ConfigLoader configLoader = new YamlConfigLoader();

    @Override
    public void run() {
        Config config = configLoader.load(configPath());
        SqlcjCompiler compiler = new SqlcjCompiler();
        compiler.compile(config);
    }

    private Path configPath() {
        return Path.of("sqlcj.yaml");
    }
}
