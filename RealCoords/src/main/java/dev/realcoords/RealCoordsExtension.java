package dev.realcoords;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

/**
 * Extension entry point for the coordinate-origin rebasing implementation.
 *
 * The previous Bedrock-edge-only packet shifter is intentionally not installed
 * on this branch. It changed coordinates after Geyser had already made
 * precision-sensitive conversions and could leave Geyser's internal state in
 * a different coordinate space. The replacement Java-downstream hook is still
 * under development; until it is available, this extension is deliberately
 * inert with respect to world coordinates.
 */
public final class RealCoordsExtension implements Extension {
    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        this.logger().warning("[RealCoords] Coordinate rebasing is under development. "
                + "The legacy Bedrock-only coordinate shifter is disabled; no world coordinates are being changed.");
    }
}
