package com.skidreport.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * RunLogger
 *
 * Lightweight run transcript: once {@link #init(File)} is called, everything
 * written to System.out / System.err is mirrored (teed) to a timestamped log
 * file under the given directory, so a full record of every run survives on
 * disk even if the JVM -- or the whole machine -- dies mid-run.
 *
 * On top of the raw transcript it provides:
 *   - timestamped log()/warn()/error() lines,
 *   - mem() heap snapshots so memory growth across phases is visible,
 *   - a default uncaught-exception handler that logs fatal errors,
 *   - a shutdown hook that flushes the file so nothing is lost on abrupt exit.
 *
 * No external logging dependency -- intentionally tiny and JDK-8 only.
 */
public final class RunLogger {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static OutputStream fileOut;
    private static boolean initialized = false;

    private RunLogger() {}

    /**
     * Begin teeing System.out/err to a new log file under {@code logDir}.
     * Safe to call once; subsequent calls are ignored. Never throws -- if the
     * file cannot be opened the app keeps running with console-only output.
     *
     * @return the log file, or null if logging could not be set up
     */
    public static synchronized File init(File logDir) {
        if (initialized) return null;
        try {
            if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, "run-" + LocalDateTime.now().format(FILE_TS) + ".log");
            fileOut = new BufferedOutputStream(new FileOutputStream(logFile, true));

            originalOut = System.out;
            originalErr = System.err;
            System.setOut(new PrintStream(new Tee(originalOut, fileOut), true));
            System.setErr(new PrintStream(new Tee(originalErr, fileOut), true));

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                error("FATAL: uncaught exception in thread '" + t.getName() + "'", e);
                flush();
            });
            Runtime.getRuntime().addShutdownHook(new Thread(RunLogger::flush));

            initialized = true;
            log("Logging to " + logFile.getAbsolutePath());
            return logFile;
        } catch (IOException e) {
            System.err.println("[WARN] Could not open log file in " + logDir + ": " + e.getMessage());
            return null;
        }
    }

    public static synchronized void log(String msg) {
        System.out.println(LocalDateTime.now().format(TS) + "  " + msg);
    }

    public static synchronized void warn(String msg) {
        System.out.println(LocalDateTime.now().format(TS) + "  [WARN] " + msg);
    }

    public static synchronized void error(String msg, Throwable t) {
        System.out.println(LocalDateTime.now().format(TS) + "  [ERROR] " + msg);
        if (t != null) t.printStackTrace(System.out);
    }

    /** Heap snapshot so memory use per phase is visible in the log. */
    public static synchronized void mem(String label) {
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        long used = (rt.totalMemory() - rt.freeMemory()) / mb;
        long committed = rt.totalMemory() / mb;
        long max = rt.maxMemory() / mb;
        log(String.format("MEM [%s] used=%dMB committed=%dMB max=%dMB", label, used, committed, max));
    }

    public static synchronized void flush() {
        try {
            System.out.flush();
            if (fileOut != null) fileOut.flush();
        } catch (IOException ignored) {
            // nothing useful to do while flushing the log
        }
    }

    /** Duplicates every byte to two underlying streams (console + file). */
    private static final class Tee extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        Tee(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override public void write(int x) throws IOException {
            a.write(x);
            b.write(x);
        }

        @Override public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override public void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}
