package com.langkraft.data

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Separate JVM deliberately has no application registry; this probes the OS lock itself. */
internal class NativeFileLockProbe(directory: File) {
    private val source = File(directory, "NativeLockProbe.java").apply {
        writeText("""
            import java.nio.channels.*;
            import java.nio.file.*;
            class NativeLockProbe {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        FileLock lock = channel.tryLock();
                        if (lock != null && args.length > 2) {
                            try (FileChannel partial = FileChannel.open(Path.of(args[2]), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                                partial.write(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3}));
                                partial.force(true);
                            }
                        }
                        System.out.println(lock == null ? "BUSY" : "HELD");
                        System.out.flush();
                        if (lock != null && args.length > 1) {
                            System.in.read();
                            Runtime.getRuntime().halt(0);
                        }
                    }
                }
            }
        """.trimIndent())
    }

    fun start(file: File, hold: Boolean = false, partial: File? = null): Process {
        val binary = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val arguments = mutableListOf(binary.path, source.path, file.path)
        if (hold || partial != null) arguments.add("hold")
        if (partial != null) arguments.add(partial.path)
        return ProcessBuilder(arguments).redirectErrorStream(true).start()
    }

    fun ready(process: Process): String = CompletableFuture.supplyAsync {
        process.inputStream.bufferedReader().readLine()
    }.get(20, TimeUnit.SECONDS) ?: error("Native probe ended without a result")

    fun stop(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Native probe did not stop" }
    }
}
