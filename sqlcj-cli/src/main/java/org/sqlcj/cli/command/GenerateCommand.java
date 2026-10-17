package org.sqlcj.cli.command;

import org.sqlcj.compiler.CompilationException;
import org.sqlcj.compiler.SqlcjCompiler;
import org.sqlcj.config.Config;
import org.sqlcj.config.ConfigLoader;
import org.sqlcj.config.ConfigurationException;
import org.sqlcj.config.YamlConfigLoader;
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
