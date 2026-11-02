package dev.realcoords;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.mcprotocollib.network.ClientSession;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Extension entry point. The startup agent invokes the bridge immediately before
 * Geyser registers its own downstream listeners.
 */
public final class RealCoordsExtension implements Extension {
    private RealCoordsLog log;
    private boolean hookRegistered;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        try {
            this.log = RealCoordsLog.open(this.dataFolder());
            this.log.info("[RealCoords] Dedicated file logger initialized.");
            Class<?> bridge = Class.forName("dev.realcoords.agent.AgentBridge", true,
                    ClassLoader.getSystemClassLoader());
            Method register = bridge.getMethod("register", BiConsumer.class);
            register.invoke(null, (BiConsumer<Object, Object>) this::onDownstreamCreated);
            this.hookRegistered = true;
            this.log.info("[RealCoords] Registered callback with the startup agent. "
                    + "Official Geyser files remain unchanged.");
        } catch (Throwable error) {
            this.logger().severe("[RealCoords] Startup agent is missing or incompatible; "
                    + "coordinate rebasing is disabled.", error);
            if (this.log != null) {
                this.log.error("[RealCoords] Could not register the startup-agent callback.", error);
            }
        }
    }

    private void onDownstreamCreated(Object geyserSession, Object downstreamSession) {
        if (!hookRegistered || log == null) {
            return;
        }
        ClientSession session = (ClientSession) downstreamSession;
        try {
            // This runs synchronously before Geyser adds ClientListener and GeyserSessionAdapter.
            session.addListener(new DownstreamSessionHook(log));
            log.debug("[RealCoords] Registered downstream SessionAdapter before Geyser listeners.");
        } catch (Throwable error) {
            log.error("[RealCoords] Failed to register downstream hook; disconnecting safely.", error);
            session.disconnect("RealCoords could not initialize its downstream packet hook.", error);
        }
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (log != null) {
            log.info("[RealCoords] Geyser is shutting down; closing the dedicated log.");
            log.close();
            log = null;
        }
    }
}
