package dev.sqlcj;

import dev.sqlcj.cli.SqlcjCommand;
import picocli.CommandLine;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SqlcjCommand sqlcjCommand = new SqlcjCommand();
        CommandLine commandLine = new CommandLine(sqlcjCommand);
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
