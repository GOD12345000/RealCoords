package dev.realcoords;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Dedicated rotating file logger for RealCoords.
 *
 * <p>Parent handlers are disabled so packet diagnostics do not get mixed into
 * the Velocity or Geyser console output.</p>
 */
public final class RealCoordsLog implements AutoCloseable {
    private static final String LOGGER_NAME = "dev.realcoords.file";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int FILE_COUNT = 4;

    private final Logger logger;
    private final FileHandler handler;

    private RealCoordsLog(Logger logger, FileHandler handler) {
        this.logger = logger;
        this.handler = handler;
    }

    public static RealCoordsLog open(Path extensionDataFolder) throws IOException {
        Path logDirectory = extensionDataFolder.resolve("logs");
        Files.createDirectories(logDirectory);

        Logger logger = Logger.getLogger(LOGGER_NAME);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        // Avoid duplicate handlers after an extension reload.
        for (Handler oldHandler : logger.getHandlers()) {
            logger.removeHandler(oldHandler);
            oldHandler.close();
        }

        FileHandler handler = new FileHandler(
                logDirectory.resolve("realcoords-%g.log").toString(),
                MAX_BYTES,
                FILE_COUNT,
                true
        );
        handler.setEncoding(StandardCharsets.UTF_8.name());
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);

        return new RealCoordsLog(logger, handler);
    }

    public void info(String message) {
        logger.info(message);
    }

    public void debug(String message) {
        logger.fine(message);
    }

    public void error(String message, Throwable error) {
        logger.log(Level.SEVERE, message, error);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        handler.close();
    }
}
