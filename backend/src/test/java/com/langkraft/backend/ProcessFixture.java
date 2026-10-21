package com.langkraft.backend;

import java.nio.file.Files;
import java.nio.file.Path;

/** A real child process with no external tools/network dependency. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("exit")) System.exit(7);
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}
