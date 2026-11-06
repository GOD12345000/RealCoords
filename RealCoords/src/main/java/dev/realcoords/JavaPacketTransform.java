package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunksBiomesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundExplodePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockDestructionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLightUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundSetBorderCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundInitializeBorderPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkBiomeData;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase-one Java protocol coordinate conversion.
 *
 * Only packet classes explicitly handled here are rewritten. Unsupported absolute
 * coordinate packets are intentionally not guessed; they are tracked by the
 * JavaSessionHook probe and will be added in follow-up coverage.
 */
final class JavaPacketTransform {
    private final JavaOriginState origin;
    private int viewDistanceChunks = 10;
    private Key currentWorld;

    JavaPacketTransform(ShiftConfig config) {
        this(new JavaOriginState(config));
    }

    JavaPacketTransform(JavaOriginState origin) {
        this.origin = origin;
    }

    Packet clientbound(Packet packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            origin.login();
            currentWorld = login.getCommonPlayerSpawnInfo().getWorldName();
            origin.respawn(dimensionId(currentWorld.asString()));
            viewDistanceChunks = login.getViewDistance();
            return login.withCommonPlayerSpawnInfo(shiftSpawnInfo(login.getCommonPlayerSpawnInfo()));
        }
        if (packet instanceof ClientboundRespawnPacket respawn) {
            currentWorld = respawn.getCommonPlayerSpawnInfo().getWorldName();
            origin.respawn(dimensionId(currentWorld.asString()));
            return respawn.withCommonPlayerSpawnInfo(shiftSpawnInfo(respawn.getCommonPlayerSpawnInfo()));
        }
        if (packet instanceof ClientboundPlayerPositionPacket position) {
            boolean relativeX = position.getRelatives().contains(PositionElement.X);
            boolean relativeZ = position.getRelatives().contains(PositionElement.Z);
            if (!relativeX && !relativeZ) {
                origin.absolutePosition(position.getPosition().getX(), position.getPosition().getZ(), viewDistanceChunks);
            }
            Vector3d p = position.getPosition();
            return position.withPosition(Vector3d.from(
                    relativeX ? p.getX() : p.getX() - origin.offsetX(),
                    p.getY(),
                    relativeZ ? p.getZ() : p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Vector3d p = teleport.getPosition();
            boolean relativeX = teleport.getRelatives().contains(PositionElement.X);
            boolean relativeZ = teleport.getRelatives().contains(PositionElement.Z);
            return teleport.withPosition(Vector3d.from(
                    relativeX ? p.getX() : p.getX() - origin.offsetX(),
                    p.getY(),
                    relativeZ ? p.getZ() : p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundPlayerLookAtPacket lookAt) {
            return lookAt.withX(lookAt.getX() - origin.offsetX()).withZ(lookAt.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundMoveVehiclePacket vehicle) {
            Vector3d p = vehicle.getPosition();
            return vehicle.withPosition(Vector3d.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundAddEntityPacket entity) {
            return entity.withX(entity.getX() - origin.offsetX()).withZ(entity.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            if (sync.isStepped()) {
                List<ClientboundEntityPositionSyncPacket.PositionStep> shifted = new ArrayList<>();
                for (ClientboundEntityPositionSyncPacket.PositionStep step : sync.getSteps()) {
                    Vector3d p = step.position();
                    shifted.add(new ClientboundEntityPositionSyncPacket.PositionStep(
                            Vector3d.from(p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()),
                            step.tickOffset()));
                }
                return sync.withSteps(shifted);
            }
            Vector3d p = sync.getEndPosition();
            return sync.withEndPosition(Vector3d.from(
                    p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            return chunk.withX(chunk.getX() - origin.offsetX() / 16)
                    .withZ(chunk.getZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket chunk) {
            return chunk.withX(chunk.getX() - origin.offsetX() / 16)
                    .withZ(chunk.getZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundSetDefaultSpawnPositionPacket spawn) {
            return spawn.withGlobalPos(shiftGlobalPos(spawn.getGlobalPos()));
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket center) {
            return center.withChunkX(center.getChunkX() - origin.offsetX() / 16)
                    .withChunkZ(center.getChunkZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundLightUpdatePacket light) {
            return light.withX(light.getX() - origin.offsetX() / 16)
                    .withZ(light.getZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundBlockUpdatePacket block) {
            BlockChangeEntry entry = block.getEntry();
            Vector3i p = entry.getPosition();
            return block.withEntry(new BlockChangeEntry(
                    Vector3i.from(p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()),
                    entry.getBlock()));
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            int chunkX = section.getChunkX() - origin.offsetX() / 16;
            int chunkZ = section.getChunkZ() - origin.offsetZ() / 16;
            BlockChangeEntry[] entries = section.getEntries().clone();
            for (int i = 0; i < entries.length; i++) {
                BlockChangeEntry entry = entries[i];
                Vector3i p = entry.getPosition();
                entries[i] = new BlockChangeEntry(
                        Vector3i.from(p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()),
                        entry.getBlock());
            }
            return section.withChunkX(chunkX).withChunkZ(chunkZ).withEntries(entries);
        }
        if (packet instanceof ClientboundChunksBiomesPacket biomes) {
            List<ChunkBiomeData> shifted = new ArrayList<>();
            for (ChunkBiomeData entry : biomes.getChunkBiomeData()) {
                shifted.add(new ChunkBiomeData(entry.getX() - origin.offsetX() / 16,
                        entry.getZ() - origin.offsetZ() / 16, entry.getBuffer()));
            }
            return new ClientboundChunksBiomesPacket(shifted);
        }
        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            return particles.withX(particles.getX() - origin.offsetX())
                    .withZ(particles.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundSoundPacket sound) {
            return sound.withX(sound.getX() - origin.offsetX()).withZ(sound.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundExplodePacket explosion) {
            Vector3d p = explosion.getCenter();
            return explosion.withCenter(Vector3d.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundOpenSignEditorPacket sign) {
            Vector3i p = sign.getPosition();
            return sign.withPosition(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
            Vector3i p = blockEntity.getPosition();
            return blockEntity.withPosition(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundBlockEventPacket event) {
            Vector3i p = event.getPosition();
            return event.withPosition(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundBlockDestructionPacket destruction) {
            Vector3i p = destruction.getPosition();
            return destruction.withPosition(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundInitializeBorderPacket border) {
            return border.withNewCenterX(border.getNewCenterX() - origin.offsetX())
                    .withNewCenterZ(border.getNewCenterZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundSetBorderCenterPacket border) {
            return border.withNewCenterX(border.getNewCenterX() - origin.offsetX())
                    .withNewCenterZ(border.getNewCenterZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundLevelEventPacket event) {
            Vector3i p = event.getPosition();
            return event.withPosition(Vector3i.from(
                    p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()));
        }
        return packet;
    }

    Packet serverbound(Packet packet) {
        if (packet instanceof ServerboundUseItemOnPacket use) {
            Vector3i p = use.getPosition();
            return use.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundPlayerActionPacket action) {
            Vector3i p = action.getPosition();
            return action.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundPickItemFromBlockPacket pick) {
            Vector3i p = pick.getPos();
            return pick.withPos(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundSignUpdatePacket sign) {
            Vector3i p = sign.getPosition();
            return sign.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundMoveVehiclePacket move) {
            Vector3d p = move.getPosition();
            return move.withPosition(Vector3d.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundMovePlayerPosPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            if (!origin.acceptServerboundPosition(realX, realZ, 64.0)) return null;
            return move.withX(realX).withZ(realZ);
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            if (!origin.acceptServerboundPosition(realX, realZ, 64.0)) return null;
            return move.withX(realX).withZ(realZ);
        }
        return packet;
    }

    int offsetX() {
        return origin.offsetX();
    }

    int offsetZ() {
        return origin.offsetZ();
    }

    private PlayerSpawnInfo shiftSpawnInfo(PlayerSpawnInfo info) {
        GlobalPos death = info.getLastDeathPos();
        GlobalPos shiftedDeath = shiftGlobalPos(death);
        return new PlayerSpawnInfo(info.getDimension(), info.getWorldName(), info.getHashedSeed(),
                info.getGameMode(), info.getPreviousGamemode(), info.isDebug(), info.isFlat(),
                shiftedDeath, info.getPortalCooldown(), info.getSeaLevel());
    }

    private GlobalPos shiftGlobalPos(GlobalPos pos) {
        if (pos == null || currentWorld == null || !currentWorld.equals(pos.getDimension())) return pos;
        Vector3i p = pos.getPosition();
        return new GlobalPos(pos.getDimension(),
                Vector3i.from(p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()));
    }

    private static int dimensionId(String worldName) {
        return switch (worldName) {
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 0;
        };
    }
}
