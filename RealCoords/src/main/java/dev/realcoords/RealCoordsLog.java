package dev.realcoords;

import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Writes RealCoords diagnostics to a dedicated rotating log file. */
final class RealCoordsLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final ExtensionLogger console;
    private final Logger file;

    RealCoordsLog(Path dataFolder, ExtensionLogger console) throws IOException {
        this.console = console;
        Path directory = dataFolder.resolve("logs");
        Files.createDirectories(directory);

        file = Logger.getLogger("RealCoords-" + dataFolder.toAbsolutePath());
        file.setUseParentHandlers(false);
        file.setLevel(java.util.logging.Level.ALL);
        FileHandler handler = new FileHandler(directory.resolve("realcoords-%g.log").toString(),
                4 * 1024 * 1024, 4, true);
        handler.setLevel(java.util.logging.Level.ALL);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return TIME.format(LocalDateTime.now()) + " [" + record.getLevel() + "] "
                        + formatMessage(record) + System.lineSeparator();
            }
        });
        file.addHandler(handler);
    }

    void info(String message) {
        file.info(message);
    }

    void debug(String message) {
        file.fine(message);
    }

    void warning(String message) {
        file.warning(message);
        console.warning(message);
    }

    void error(String message, Throwable error) {
        file.log(java.util.logging.Level.SEVERE, message, error);
        console.error(message, error);
    }
}
