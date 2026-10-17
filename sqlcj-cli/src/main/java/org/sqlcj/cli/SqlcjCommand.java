package org.sqlcj.cli;

import org.sqlcj.cli.command.GenerateCommand;
import org.sqlcj.cli.command.VersionCommand;
import picocli.CommandLine.Command;

@Command(
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
