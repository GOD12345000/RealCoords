package dev.realcoords;

import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Dedicated RealCoords file logger. Deliberately does not use java.util.logging:
 * Geyser may attach its console handler to the extension logger with a shared name.
 */
final class RealCoordsLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_BACKUPS = 4;

    private final ExtensionLogger console;
    private final Path logFile;

    RealCoordsLog(Path dataFolder, ExtensionLogger console) throws IOException {
        this.console = console;
        Path directory = dataFolder.resolve("logs");
        Files.createDirectories(directory);
        this.logFile = directory.resolve("realcoords.log");
        if (!Files.exists(logFile)) Files.createFile(logFile);
    }

    synchronized void info(String message) {
        write("INFO", message, null);
    }

    synchronized void debug(String message) {
        write("DEBUG", message, null);
    }

    synchronized void warning(String message) {
        write("WARN", message, null);
        console.warning(message);
    }

    synchronized void error(String message, Throwable error) {
        write("ERROR", message, error);
        console.error(message, error);
    }

    private void write(String level, String message, Throwable error) {
        StringBuilder line = new StringBuilder()
                .append(TIME.format(LocalDateTime.now()))
                .append(" [").append(level).append("] ")
                .append(message == null ? "" : message)
                .append(System.lineSeparator());

        if (error != null) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            line.append(trace);
        }

        byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
        try {
            rotateIfNeeded(bytes.length);
            Files.write(logFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Only a file-system failure should fall back to the Velocity console.
            console.error("[RealCoords] 無法寫入獨立日誌 " + logFile + ": " + e.getMessage(), e);
        }
    }

    private void rotateIfNeeded(int incomingBytes) throws IOException {
        if (!Files.exists(logFile) || Files.size(logFile) + incomingBytes <= MAX_BYTES) return;

        Path oldest = logFile.resolveSibling("realcoords." + MAX_BACKUPS + ".log");
        Files.deleteIfExists(oldest);
        for (int i = MAX_BACKUPS - 1; i >= 1; i--) {
            Path source = logFile.resolveSibling("realcoords." + i + ".log");
            if (Files.exists(source)) {
                Files.move(source, logFile.resolveSibling("realcoords." + (i + 1) + ".log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(logFile, logFile.resolveSibling("realcoords.1.log"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createFile(logFile);
    }
}
