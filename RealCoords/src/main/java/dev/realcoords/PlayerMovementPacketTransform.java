package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundGameTestHighlightPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.debug.ClientboundDebugBlockValuePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLightUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundExplodePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundAddTransientBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockDestructionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundBlockEntityTagQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSetTestBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundJigsawGeneratePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundTestInstanceBlockActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetJigsawBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetStructureBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;

/**
 * Pure entity, player, vehicle, and block-interaction packet transformations for one immutable origin.
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
        if (packet instanceof ClientboundEntityPositionSyncPacket syncPacket) {
            if (syncPacket.isStepped()) {
                java.util.List<ClientboundEntityPositionSyncPacket.PositionStep> localSteps = syncPacket.getSteps().stream()
                        .map(step -> new ClientboundEntityPositionSyncPacket.PositionStep(
                                toLocalPosition(step.position(), origin), step.tickOffset()))
                        .toList();
                return syncPacket.withSteps(localSteps);
            }
            return syncPacket.withEndPosition(toLocalPosition(syncPacket.getEndPosition(), origin));
        }
        if (packet instanceof ClientboundMoveVehiclePacket vehiclePacket) {
            return vehiclePacket.withPosition(toLocalPosition(vehiclePacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundDamageEventPacket damagePacket && damagePacket.getSourcePosition() != null) {
            return new ClientboundDamageEventPacket(
                    damagePacket.getEntityId(), damagePacket.getSourceTypeId(), damagePacket.getSourceCauseId(),
                    damagePacket.getSourceDirectId(), toLocalPosition(damagePacket.getSourcePosition(), origin));
        }
        if (packet instanceof ClientboundOpenSignEditorPacket signEditorPacket) {
            return signEditorPacket.withPosition(toLocalBlockPosition(signEditorPacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundAddTransientBlockPacket transientBlockPacket) {
            return transientBlockPacket.withPosition(toLocalBlockPosition(transientBlockPacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundBlockDestructionPacket destructionPacket) {
            return destructionPacket.withPosition(toLocalBlockPosition(destructionPacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundGameTestHighlightPosPacket gameTestPacket) {
            return gameTestPacket.withAbsolutePos(toLocalBlockPosition(gameTestPacket.getAbsolutePos(), origin));
        }
        if (packet instanceof ClientboundDebugBlockValuePacket debugBlockPacket) {
            return debugBlockPacket.withBlockPos(toLocalBlockPosition(debugBlockPacket.getBlockPos(), origin));
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            return chunkPacket
                    .withX(origin.toLocalChunkX(chunkPacket.getX()))
                    .withZ(origin.toLocalChunkZ(chunkPacket.getZ()));
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
            return forgetPacket
                    .withX(origin.toLocalChunkX(forgetPacket.getX()))
                    .withZ(origin.toLocalChunkZ(forgetPacket.getZ()));
        }
        if (packet instanceof ClientboundLightUpdatePacket lightPacket) {
            return lightPacket
                    .withX(origin.toLocalChunkX(lightPacket.getX()))
                    .withZ(origin.toLocalChunkZ(lightPacket.getZ()));
        }
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
            BlockChangeEntry entry = blockUpdatePacket.getEntry();
            return blockUpdatePacket.withEntry(new BlockChangeEntry(
                    toLocalBlockPosition(entry.getPosition(), origin), entry.getBlock()
            ));
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdatePacket) {
            BlockChangeEntry[] entries = sectionUpdatePacket.getEntries();
            BlockChangeEntry[] localEntries = new BlockChangeEntry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                localEntries[i] = new BlockChangeEntry(
                        toLocalBlockPosition(entries[i].getPosition(), origin), entries[i].getBlock()
                );
            }
            return sectionUpdatePacket
                    .withChunkX(origin.toLocalChunkX(sectionUpdatePacket.getChunkX()))
                    .withChunkZ(origin.toLocalChunkZ(sectionUpdatePacket.getChunkZ()))
                    .withEntries(localEntries);
        }
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityPacket) {
            return blockEntityPacket.withPosition(toLocalBlockPosition(blockEntityPacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundBlockEventPacket blockEventPacket) {
            return blockEventPacket.withPosition(toLocalBlockPosition(blockEventPacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundLevelEventPacket levelEventPacket) {
            return levelEventPacket.withPosition(toLocalBlockPosition(levelEventPacket.getPosition(), origin));
        }
        if (packet instanceof ClientboundExplodePacket explodePacket) {
            Vector3d center = explodePacket.getCenter();
            return explodePacket.withCenter(Vector3d.from(
                    origin.toLocalX(center.getX()), center.getY(), origin.toLocalZ(center.getZ())
            ));
        }
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
        if (packet instanceof ServerboundBlockEntityTagQueryPacket queryPacket) {
            return queryPacket.withPosition(toRealBlockPosition(queryPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundSetTestBlockPacket testBlockPacket) {
            return testBlockPacket.withPosition(toRealBlockPosition(testBlockPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundJigsawGeneratePacket jigsawGeneratePacket) {
            return jigsawGeneratePacket.withPosition(toRealBlockPosition(jigsawGeneratePacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundSetStructureBlockPacket structureBlockPacket) {
            return structureBlockPacket.withPosition(toRealBlockPosition(structureBlockPacket.getPosition(), origin));
        }
        if (packet instanceof ServerboundPickItemFromBlockPacket pickBlockPacket) {
            return pickBlockPacket.withPos(toRealBlockPosition(pickBlockPacket.getPos(), origin));
        }
        if (packet instanceof ServerboundTestInstanceBlockActionPacket testInstancePacket) {
            return testInstancePacket.withPos(toRealBlockPosition(testInstancePacket.getPos(), origin));
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

    private static Vector3d toLocalPosition(Vector3d realPosition, CoordinateTransform origin) {
        return Vector3d.from(
                origin.toLocalX(realPosition.getX()), realPosition.getY(), origin.toLocalZ(realPosition.getZ())
        );
    }

    private static Vector3i toLocalBlockPosition(Vector3i realPosition, CoordinateTransform origin) {
        return Vector3i.from(
                origin.toLocalBlockX(realPosition.getX()),
                realPosition.getY(),
                origin.toLocalBlockZ(realPosition.getZ())
        );
    }

    private static Vector3i toRealBlockPosition(Vector3i localPosition, CoordinateTransform origin) {
        return Vector3i.from(
                origin.toRealBlockX(localPosition.getX()),
                localPosition.getY(),
                origin.toRealBlockZ(localPosition.getZ())
        );
    }
}
