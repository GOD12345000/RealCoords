package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetJigsawBlockPacket;

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
        if (packet instanceof ClientboundAddEntityPacket spawnPacket) {
            return spawnPacket
                    .withX(origin.toLocalX(spawnPacket.getX()))
                    .withZ(origin.toLocalZ(spawnPacket.getZ()));
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleportPacket) {
            boolean relativeX = teleportPacket.getRelatives().contains(PositionElement.X);
            boolean relativeZ = teleportPacket.getRelatives().contains(PositionElement.Z);
            Vector3d position = teleportPacket.getPosition();
            Vector3d localPosition = Vector3d.from(
                    origin.toLocalX(position.getX(), relativeX),
                    position.getY(),
                    origin.toLocalZ(position.getZ(), relativeZ)
            );
            return teleportPacket.withPosition(localPosition);
        }
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
        if (packet instanceof ServerboundMoveVehiclePacket vehiclePacket) {
            Vector3d position = vehiclePacket.getPosition();
            return vehiclePacket.withPosition(Vector3d.from(
                    origin.toRealX(position.getX()), position.getY(), origin.toRealZ(position.getZ())
            ));
        }
        if (packet instanceof ServerboundUseItemOnPacket useItemPacket) {
            return useItemPacket.withPosition(toRealBlockPosition(useItemPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundPlayerActionPacket actionPacket) {
            return actionPacket.withPosition(toRealBlockPosition(actionPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundSetCommandBlockPacket commandBlockPacket) {
            return commandBlockPacket.withPosition(toRealBlockPosition(commandBlockPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundSetJigsawBlockPacket jigsawBlockPacket) {
            return jigsawBlockPacket.withPosition(toRealBlockPosition(jigsawBlockPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundSignUpdatePacket signUpdatePacket) {
            return signUpdatePacket.withPosition(toRealBlockPosition(signUpdatePacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundAcceptTeleportationPacket teleportPacket) {
            return teleportPacket
                    .withX(origin.toRealX(teleportPacket.getX()))
                    .withZ(origin.toRealZ(teleportPacket.getZ()));
        }
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

    private static Vector3i toRealBlockPosition(Vector3i localPosition, CoordinateTransform origin) {
        return Vector3i.from(
                origin.toRealBlockX(localPosition.getX()),
                localPosition.getY(),
                origin.toRealBlockZ(localPosition.getZ())
        );
    }
}
