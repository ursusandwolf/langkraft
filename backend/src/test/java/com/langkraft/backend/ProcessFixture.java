package com.langkraft.backend;

import java.nio.file.Files;
import java.nio.file.Path;

/** A real child process with no external tools/network dependency. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("exit")) System.exit(7);
        if (args[0].equals("spawn")) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), args[2]).inheritIO().start();
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
            return;
        }
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}
