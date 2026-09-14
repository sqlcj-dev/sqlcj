package dev.sqlcj.cli.command;

import dev.sqlcj.compiler.CompilationException;
import dev.sqlcj.compiler.SqlcjCompiler;
import dev.sqlcj.config.Config;
import dev.sqlcj.config.ConfigLoader;
import dev.sqlcj.config.ConfigurationException;
import dev.sqlcj.config.YamlConfigLoader;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "generate",
        description = "Generate Java sources from SQL queries"
)
public class GenerateCommand implements Callable<Integer> {

    private static final String CONFIG_FILE = "sqlcj.yaml";

    private final ConfigLoader configLoader = new YamlConfigLoader();

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        try {
            Config config = configLoader.load(configPath());
            SqlcjCompiler compiler = new SqlcjCompiler();
            compiler.compile(config);
            return CommandLine.ExitCode.OK;
        } catch (ConfigurationException | CompilationException e) {
            spec.commandLine()
                    .getErr()
                    .println("sqlcj: " + e.getMessage());

            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private Path configPath() {
        return Path.of(CONFIG_FILE);
    }
}
