package dev.realcoords;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.downstream.DownstreamSessionInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.io.IOException;

/**
 * Extension entry point for coordinate-origin rebasing.
 *
 * <p>The extension currently installs only a passive Java downstream packet
 * bridge. It does not modify packets or coordinates yet.</p>
 */
public final class RealCoordsExtension implements Extension {
    private RealCoordsLog log;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        try {
            this.log = RealCoordsLog.open(this.dataFolder());
            this.log.info("[RealCoords] Dedicated file logger initialized. Console logging is disabled.");
            this.log.info("[RealCoords] Coordinate rebasing is not active; the downstream bridge is observation-only.");
        } catch (IOException error) {
            this.logger().severe("[RealCoords] Could not initialize the dedicated log file. "
                    + "The downstream hook will fail closed.", error);
        }
    }

    @Subscribe
    public void onDownstreamSessionInitialize(DownstreamSessionInitializeEvent event) {
        if (this.log == null) {
            throw new IllegalStateException("RealCoords dedicated logger is unavailable; refusing downstream initialization");
        }

        // This callback runs before Geyser registers ClientListener. The
        // SessionAdapter therefore receives ConnectedEvent first and can put
        // the Netty bridge in place before the initial Java handshake is sent.
        event.downstreamSession().addListener(new DownstreamSessionHook(this.log));
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (this.log != null) {
            this.log.info("[RealCoords] Geyser is shutting down; closing the dedicated log.");
            this.log.close();
            this.log = null;
        }
    }
}
