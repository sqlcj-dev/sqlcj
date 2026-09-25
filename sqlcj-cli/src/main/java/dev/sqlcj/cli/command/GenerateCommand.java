package dev.sqlcj.cli.command;

import dev.sqlcj.compiler.CompilationException;
import dev.sqlcj.compiler.SqlcjCompiler;
import dev.sqlcj.config.Config;
import dev.sqlcj.config.ConfigLoader;
import dev.sqlcj.config.ConfigurationException;
import dev.sqlcj.config.YamlConfigLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "generate",
    description = "Generate Java sources from SQL queries"
)
public class GenerateCommand implements Callable<Integer> {

    private final ConfigLoader configLoader = new YamlConfigLoader();

    @Spec
    CommandSpec spec;

    @Option(
        names = "--config",
        paramLabel = "<path>",
        defaultValue = "sqlcj.yaml",
        description = """
            Configuration file to generate from. A relative path is \
            resolved against the current directory, while relative paths \
            inside the file are resolved against its own directory. \
            Default: ${DEFAULT-VALUE}
            """
    )
    Path configFile;

    @Override
    public Integer call() {
        try {
            Config config = configLoader.load(configFile);
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
}
