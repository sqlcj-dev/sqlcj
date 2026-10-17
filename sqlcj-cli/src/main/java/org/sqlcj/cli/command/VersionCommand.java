package org.sqlcj.cli.command;

import org.sqlcj.Main;
import picocli.CommandLine.Command;

@Command(
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
