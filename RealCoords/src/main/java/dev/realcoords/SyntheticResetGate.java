package dev.realcoords;

import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

/**
 * Narrow correlation gate for the two Java-bound responses caused by one synthetic
 * Geyser world reset. It never suppresses an acknowledgement with another ID.
 *
 * The gate is channel-confined: begin, shouldSuppress, and abort must run on the
 * downstream channel event loop.
 */
final class SyntheticResetGate {
    private boolean active;
    private int playerLoadedPacketsPending;
    private boolean teleportAckPending;
    private int teleportId;

    boolean begin(int reservedTeleportId) {
        return begin(reservedTeleportId, 1);
    }

    boolean begin(int reservedTeleportId, int expectedPlayerLoadedPackets) {
        if (active || expectedPlayerLoadedPackets < 1) return false;
        active = true;
        playerLoadedPacketsPending = expectedPlayerLoadedPackets;
        teleportAckPending = true;
        teleportId = reservedTeleportId;
        return true;
    }

    boolean shouldSuppress(Packet packet) {
        if (!active) return false;

        if (playerLoadedPacketsPending > 0 && packet == ServerboundPlayerLoadedPacket.INSTANCE) {
            playerLoadedPacketsPending--;
            finishIfComplete();
            return true;
        }

        if (teleportAckPending && packet instanceof ServerboundAcceptTeleportationPacket ack
                && ack.getId() == teleportId) {
            teleportAckPending = false;
            finishIfComplete();
            return true;
        }
        return false;
    }

    void abort() {
        active = false;
        playerLoadedPacketsPending = 0;
        teleportAckPending = false;
        teleportId = 0;
    }

    boolean active() {
        return active;
    }

    private void finishIfComplete() {
        if (playerLoadedPacketsPending == 0 && !teleportAckPending) abort();
    }
}
