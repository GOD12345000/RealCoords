package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

/**
 * Pure player-position packet transformations for one immutable origin.
 *
 * <p>Inbound packets travel from the Java server to Geyser. Outbound packets
 * travel from Geyser to the Java server. This class is deliberately not wired
 * into the live Netty bridge until origin lifecycle and world-state transforms
 * are ready.</p>
 */
public final class PlayerMovementPacketTransform {
    private PlayerMovementPacketTransform() {
    }

    /**
     * Convert Java-server positions into Geyser's local coordinate space.
     * Relative X/Z components are deltas and must remain unchanged.
     */
    public static Packet toLocal(Packet packet, CoordinateTransform origin) {
        if (packet instanceof ClientboundPlayerPositionPacket positionPacket) {
            boolean relativeX = positionPacket.getRelatives().contains(PositionElement.X);
            boolean relativeZ = positionPacket.getRelatives().contains(PositionElement.Z);
            Vector3d position = positionPacket.getPosition();
            Vector3d localPosition = Vector3d.from(
                    origin.toLocalX(position.getX(), relativeX),
                    position.getY(),
                    origin.toLocalZ(position.getZ(), relativeZ)
            );
            return positionPacket.withPosition(localPosition);
        }
        return packet;
    }

    /**
     * Restore Geyser-local absolute positions before they reach the Java server.
     * Movement deltas, rotation-only packets, and status-only packets are untouched.
     */
    public static Packet toReal(Packet packet, CoordinateTransform origin) {
        if (packet instanceof ServerboundMovePlayerPosPacket positionPacket) {
            return positionPacket
                    .withX(origin.toRealX(positionPacket.getX()))
                    .withZ(origin.toRealZ(positionPacket.getZ()));
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket positionRotationPacket) {
            return positionRotationPacket
                    .withX(origin.toRealX(positionRotationPacket.getX()))
                    .withZ(origin.toRealZ(positionRotationPacket.getZ()));
        }
        return packet;
    }
}
