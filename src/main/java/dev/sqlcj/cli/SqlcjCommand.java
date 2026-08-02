package dev.sqlcj.cli;

import dev.sqlcj.cli.command.GenerateCommand;
import dev.sqlcj.cli.command.VersionCommand;
import picocli.CommandLine;

@CommandLine.Command(
        name = "sqlcj",
        mixinStandardHelpOptions = true,
        description = "SQL compiler and type-safe code generator for Java",
        subcommands = {
                VersionCommand.class,
                GenerateCommand.class
        }
)
public class SqlcjCommand implements Runnable {

    @Override
    public void run() {
    }
}
