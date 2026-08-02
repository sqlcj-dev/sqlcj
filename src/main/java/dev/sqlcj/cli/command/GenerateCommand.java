package dev.sqlcj.cli.command;

import picocli.CommandLine;

@CommandLine.Command(
        name = "generate",
        description = "Generate Java sources from SQL queries"
)
public class GenerateCommand implements Runnable {

    @Override
    public void run() {
    }
}
