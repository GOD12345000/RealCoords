package dev.realcoords.agent;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Small system-classloader bridge between the startup agent and the Geyser extension.
 * It deliberately uses Object so the agent does not bundle or load Geyser/MCProtocolLib classes.
 */
public final class AgentBridge {
    private static final AtomicReference<BiConsumer<Object, Object>> CALLBACK = new AtomicReference<>();
    private static volatile boolean transformed;

    private AgentBridge() {
    }

    public static void register(BiConsumer<Object, Object> callback) {
        CALLBACK.set(callback);
    }

    public static void clear() {
        CALLBACK.set(null);
    }

    public static boolean isTransformed() {
        return transformed;
    }

    static void markTransformed() {
        transformed = true;
    }

    /**
     * Called immediately before Geyser registers its first downstream session listener.
     */
    public static void beforeFirstListener(Object geyserSession, Object downstreamSession) {
        BiConsumer<Object, Object> callback = CALLBACK.get();
        if (callback != null) {
            callback.accept(geyserSession, downstreamSession);
        }
    }
}
