package dev.sqlcj.cli.command;

import dev.sqlcj.Main;
import picocli.CommandLine;

@CommandLine.Command(
        name = "version",
        description = "Print SQLCJ version"
)
public class VersionCommand implements Runnable {

    @Override
    public void run() {
        String version = Main.class.getPackage().getImplementationVersion();
        System.out.println(version);
    }
}
