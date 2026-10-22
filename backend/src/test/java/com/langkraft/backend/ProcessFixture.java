package com.langkraft.backend;

import java.nio.file.Files;
import java.nio.file.Path;

/** A real child process with no external tools/network dependency. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("exit")) System.exit(7);
        if (args[0].equals("quota-tree")) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), args[3]).inheritIO().start();
            Files.writeString(Path.of(args[2]), Long.toString(ProcessHandle.current().pid()));
            for (int i = 0; i < 500; i++) {
                if (Files.exists(Path.of(args[3])) && Files.size(Path.of(args[3])) > 0) break;
                Thread.sleep(10);
            }
            Files.write(Path.of(args[1]), new byte[4096]);
            Thread.sleep(60_000);
            return;
        }
        if (args[0].equals("log-overflow")) {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            System.out.write(new byte[3 * 1024 * 1024]);
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
        if (args[0].equals("spawn") || args[0].equals("spawn-and-exit")) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), args[2]).inheritIO().start();
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(args[0].equals("spawn-and-exit") ? 500 : 60_000);
            return;
        }
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}
