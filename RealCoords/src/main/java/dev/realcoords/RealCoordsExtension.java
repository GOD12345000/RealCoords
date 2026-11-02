package dev.realcoords;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.downstream.DownstreamSessionInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

/**
 * Extension entry point for coordinate-origin rebasing.
 *
 * <p>The extension currently installs only a passive Java downstream packet
 * bridge. It does not modify packets or coordinates yet.</p>
 */
public final class RealCoordsExtension implements Extension {
    @Subscribe
    public void onDownstreamSessionInitialize(DownstreamSessionInitializeEvent event) {
        // This callback runs before Geyser registers ClientListener. The
        // SessionAdapter therefore receives ConnectedEvent first and can put
        // the Netty bridge in place before the initial Java handshake is sent.
        event.downstreamSession().addListener(new DownstreamSessionHook(this.logger()));
    }
}
