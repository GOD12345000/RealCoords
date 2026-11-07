package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.advancement.Advancement;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.LodestoneTracker;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.ChunkWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.TrackedWaypoint;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.Vec3iWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.BlockPositionSource;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundChangeDifficultyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundSetBorderSizePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundSetBorderLerpSizePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateAdvancementsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityLinkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunksBiomesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkBiomeData;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCommandBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetStructureBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetJigsawBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSetTestBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase-one Java protocol coordinate conversion.
 *
 * Only packet classes explicitly handled here are rewritten. Other packet types
 * pass through unchanged and must be reviewed before they are considered supported.
 */
final class JavaPacketTransform {
    record MovementRejection(double distance, double limit) { }

    private final JavaOriginState origin;
    private int viewDistanceChunks = 10;
    private final double maxServerboundJump;
    private final ShiftConfig config;
    private final ChunkReplayCache chunkReplayCache;
    private final EntityReplayCache entityReplayCache;
    private final GlobalStateReplayCache globalStateReplayCache;
    private final long liveUpdateBudgetBytes;
    private boolean replayingCachedPackets;
    private Key currentWorld;
    private PlayerSpawnInfo currentSpawnInfo;
    private boolean hasPlayerPosition;
    private double lastPlayerY;
    private float lastPlayerYaw;
    private float lastPlayerPitch;
    private JavaOriginState.Snapshot pendingOriginChange;
    private MovementRejection pendingMovementRejection;
    private GlobalPos lastDeathPosition;
    private boolean hasLastDeathPosition;
    private GlobalPos pendingDeathPositionUpdate;
    private boolean deathPositionUpdatePending;
    private boolean deathPositionWarningIssued;
    private boolean pendingDeathPositionWarning;

    record DeathPositionUpdate(GlobalPos position) {
    }

    JavaPacketTransform(ShiftConfig config) {
        this(new JavaOriginState(config), config.maxJump, config);
    }

    JavaPacketTransform(JavaOriginState origin) {
        this(origin, 64.0, null);
    }

    JavaPacketTransform(JavaOriginState origin, double maxServerboundJump) {
        this(origin, maxServerboundJump, null);
    }

    JavaPacketTransform(JavaOriginState origin, double maxServerboundJump, ShiftConfig config) {
        this.origin = origin;
        this.maxServerboundJump = maxServerboundJump;
        this.config = config;
        long totalBudget = config == null ? 32L * 1024 * 1024 : Math.max(1L << 20, config.cacheMaxBytes);
        long chunkBudget = totalBudget * 3 / 4;
        long entityBudget = totalBudget / 8;
        long globalBudget = totalBudget / 32;
        this.liveUpdateBudgetBytes = totalBudget * 3 / 32;
        this.chunkReplayCache = config != null && config.cacheEnabled
                ? new ChunkReplayCache(config.cacheMaxChunks, chunkBudget, config.cacheCompress) : null;
        this.entityReplayCache = config != null && config.cacheEnabled
                ? new EntityReplayCache(512, entityBudget) : null;
        this.globalStateReplayCache = config != null && config.cacheEnabled
                ? new GlobalStateReplayCache(globalBudget) : null;
    }

    Packet clientbound(Packet packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            if (chunkReplayCache != null) chunkReplayCache.clear();
            if (entityReplayCache != null) entityReplayCache.clear();
            if (globalStateReplayCache != null) globalStateReplayCache.clear();
            origin.login();
            currentSpawnInfo = login.getCommonPlayerSpawnInfo();
            currentWorld = currentSpawnInfo.getWorldName();
            lastDeathPosition = login.getCommonPlayerSpawnInfo().getLastDeathPos();
            hasLastDeathPosition = lastDeathPosition != null;
            origin.respawn(dimensionId(currentWorld.asString()));
            viewDistanceChunks = login.getViewDistance();
            return login.withCommonPlayerSpawnInfo(shiftSpawnInfo(login.getCommonPlayerSpawnInfo()));
        }
        if (packet instanceof ClientboundRespawnPacket respawn) {
            if (chunkReplayCache != null) chunkReplayCache.clear();
            if (entityReplayCache != null) entityReplayCache.clear();
            if (globalStateReplayCache != null) globalStateReplayCache.clear();
            currentSpawnInfo = respawn.getCommonPlayerSpawnInfo();
            currentWorld = currentSpawnInfo.getWorldName();
            lastDeathPosition = respawn.getCommonPlayerSpawnInfo().getLastDeathPos();
            hasLastDeathPosition = lastDeathPosition != null;
            origin.respawn(dimensionId(currentWorld.asString()));
            return respawn.withCommonPlayerSpawnInfo(shiftSpawnInfo(respawn.getCommonPlayerSpawnInfo()));
        }
        if (packet instanceof ClientboundPlayerPositionPacket position) {
            boolean relativeX = position.getRelatives().contains(PositionElement.X);
            boolean relativeZ = position.getRelatives().contains(PositionElement.Z);
            JavaOriginState.Snapshot snapshot = origin.absolutePosition(
                    position.getPosition().getX(), position.getPosition().getZ(),
                    viewDistanceChunks, !relativeX, !relativeZ);
            if (snapshot.changed()) pendingOriginChange = snapshot;
            if (snapshot.initialized() && hasLastDeathPosition) {
                pendingDeathPositionUpdate = shiftGlobalPos(lastDeathPosition);
                deathPositionUpdatePending = true;
                if (!deathPositionWarningIssued && lastDeathPosition != null
                        && currentWorld != null && currentWorld.equals(lastDeathPosition.getDimension())) {
                    Vector3i death = pendingDeathPositionUpdate.getPosition();
                    ShiftConfig.Dim dim = config.dim(dimensionId(currentWorld.asString()));
                    if (Math.abs(death.getX()) > dim.trigger || Math.abs(death.getZ()) > dim.trigger) {
                        pendingDeathPositionWarning = true;
                        deathPositionWarningIssued = true;
                    }
                }
            }
            Vector3d p = position.getPosition();
            boolean relativeY = position.getRelatives().contains(PositionElement.Y);
            boolean relativeYaw = position.getRelatives().contains(PositionElement.Y_ROT);
            boolean relativePitch = position.getRelatives().contains(PositionElement.X_ROT);
            lastPlayerY = relativeY && hasPlayerPosition ? lastPlayerY + p.getY() : p.getY();
            lastPlayerYaw = relativeYaw && hasPlayerPosition ? lastPlayerYaw + position.getYRot() : position.getYRot();
            lastPlayerPitch = relativePitch && hasPlayerPosition ? lastPlayerPitch + position.getXRot() : position.getXRot();
            hasPlayerPosition = origin.hasPosition();
            return position.withPosition(Vector3d.from(
                    relativeX ? p.getX() : p.getX() - origin.offsetX(),
                    p.getY(),
                    relativeZ ? p.getZ() : p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Vector3d p = teleport.getPosition();
            boolean relativeX = teleport.getRelatives().contains(PositionElement.X);
            boolean relativeY = teleport.getRelatives().contains(PositionElement.Y);
            boolean relativeZ = teleport.getRelatives().contains(PositionElement.Z);
            boolean relativeYaw = teleport.getRelatives().contains(PositionElement.Y_ROT);
            boolean relativePitch = teleport.getRelatives().contains(PositionElement.X_ROT);
            EntityReplayCache.Position previous = entityReplayCache == null || replayingCachedPackets
                    ? null : entityReplayCache.position(teleport.getId());
            double x = relativeX && previous != null ? previous.x() + p.getX() : p.getX();
            double y = relativeY && previous != null ? previous.y() + p.getY() : p.getY();
            double z = relativeZ && previous != null ? previous.z() + p.getZ() : p.getZ();
            float yaw = relativeYaw && previous != null ? previous.yaw() + teleport.getYRot() : teleport.getYRot();
            float pitch = relativePitch && previous != null ? previous.pitch() + teleport.getXRot() : teleport.getXRot();
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.updatePosition(teleport.getId(), x, y, z, yaw, pitch, teleport.isOnGround());
                cacheEntityPosition(teleport.getId());
            }
            return teleport.withPosition(Vector3d.from(
                    relativeX ? p.getX() : p.getX() - origin.offsetX(),
                    p.getY(),
                    relativeZ ? p.getZ() : p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundPlayerLookAtPacket lookAt) {
            if (lookAt.getTargetEntityOrigin() != null) return packet;
            return lookAt.withX(lookAt.getX() - origin.offsetX()).withZ(lookAt.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundMoveEntityPosPacket move) {
            double dx = move.getMoveX(), dy = move.getMoveY(), dz = move.getMoveZ();
            if (move.getSteps() != null) {
                dx = 0; dy = 0; dz = 0;
                for (ClientboundMoveEntityPosPacket.DeltaStep step : move.getSteps()) {
                    dx += step.moveX(); dy += step.moveY(); dz += step.moveZ();
                }
            }
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.updateDelta(move.getEntityId(), dx, dy, dz, null, null, move.isOnGround());
                cacheEntityPosition(move.getEntityId());
            }
            return packet;
        }
        if (packet instanceof ClientboundMoveEntityPosRotPacket move) {
            double dx = move.getMoveX(), dy = move.getMoveY(), dz = move.getMoveZ();
            if (move.getSteps() != null) {
                dx = 0; dy = 0; dz = 0;
                for (ClientboundMoveEntityPosRotPacket.DeltaStep step : move.getSteps()) {
                    dx += step.moveX(); dy += step.moveY(); dz += step.moveZ();
                }
            }
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.updateDelta(move.getEntityId(), dx, dy, dz,
                        move.getYaw(), move.getPitch(), move.isOnGround());
                cacheEntityPosition(move.getEntityId());
            }
            return packet;
        }
        if (packet instanceof ClientboundMoveEntityRotPacket move) {
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.updateDelta(move.getEntityId(), 0, 0, 0,
                        move.getYaw(), move.getPitch(), move.isOnGround());
                cacheEntityPosition(move.getEntityId());
            }
            return packet;
        }
        if (packet instanceof ClientboundMoveVehiclePacket vehicle) {
            Vector3d p = vehicle.getPosition();
            return vehicle.withPosition(Vector3d.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundAddEntityPacket entity) {
            cacheEntity(entity.getEntityId(), EntityReplayCache.Kind.SPAWN, 0, entity);
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.updatePosition(entity.getEntityId(), entity.getX(), entity.getY(), entity.getZ(),
                        entity.getYaw(), entity.getPitch(), false);
            }
            return entity.withX(entity.getX() - origin.offsetX()).withZ(entity.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket removed) {
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.removeEntities(removed.getEntityIds());
            }
            return packet;
        }
        if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            for (var attribute : attributes.getAttributes()) {
                ClientboundUpdateAttributesPacket single =
                        new ClientboundUpdateAttributesPacket(attributes.getEntityId(), List.of(attribute));
                cacheEntity(attributes.getEntityId(), EntityReplayCache.Kind.ATTRIBUTES,
                        attribute.getType().getId(), single);
            }
            return packet;
        }
        if (packet instanceof ClientboundUpdateMobEffectPacket effect) {
            cacheEntity(effect.getEntityId(), EntityReplayCache.Kind.EFFECT, effect.getEffect().ordinal(), effect);
            return packet;
        }
        if (packet instanceof ClientboundRemoveMobEffectPacket effect) {
            if (entityReplayCache != null && !replayingCachedPackets) {
                entityReplayCache.removeEffect(effect.getEntityId(), effect.getEffect().ordinal());
            }
            return packet;
        }
        if (packet instanceof ClientboundSetEntityLinkPacket link) {
            cacheEntity(link.getEntityId(), EntityReplayCache.Kind.LINK, 0, link);
            return packet;
        }
        if (packet instanceof ClientboundSetPassengersPacket passengers) {
            cacheEntity(passengers.getEntityId(), EntityReplayCache.Kind.PASSENGERS, 0, passengers);
            return packet;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            cacheEntity(motion.getEntityId(), EntityReplayCache.Kind.MOTION, 0, motion);
            return packet;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            if (entityReplayCache != null && !replayingCachedPackets) {
                Vector3d absolute = sync.isStepped()
                        ? (sync.getSteps().isEmpty() ? null
                                : sync.getSteps().get(sync.getSteps().size() - 1).position())
                        : sync.getEndPosition();
                if (absolute != null) {
                    entityReplayCache.updatePosition(sync.getId(), absolute.getX(), absolute.getY(), absolute.getZ(),
                            sync.getYRot(), sync.getXRot(), sync.isOnGround());
                    cacheEntityPosition(sync.getId());
                }
            }
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
            cacheChunk(chunk);
            BlockEntityInfo[] blockEntities = chunk.getBlockEntities().clone();
            for (int i = 0; i < blockEntities.length; i++) {
                BlockEntityInfo info = blockEntities[i];
                blockEntities[i] = new BlockEntityInfo(info.getX(), info.getY(), info.getZ(), info.getType(),
                        NbtShift.shift(info.getNbt(), origin.offsetX(), origin.offsetZ(), config));
            }
            return chunk.withX(chunk.getX() - origin.offsetX() / 16)
                    .withZ(chunk.getZ() - origin.offsetZ() / 16)
                    .withBlockEntities(blockEntities);
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket chunk) {
            if (chunkReplayCache != null) chunkReplayCache.remove(chunk.getX(), chunk.getZ());
            return chunk.withX(chunk.getX() - origin.offsetX() / 16)
                    .withZ(chunk.getZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundChangeDifficultyPacket difficulty) {
            cacheGlobal(GlobalStateReplayCache.Key.DIFFICULTY, difficulty);
            return packet;
        }
        if (packet instanceof ClientboundSetTimePacket time) {
            cacheGlobal(GlobalStateReplayCache.Key.TIME, time);
            return packet;
        }
        if (packet instanceof ClientboundGameEventPacket event) {
            GlobalStateReplayCache.Key key = switch (event.getNotification()) {
                case CHANGE_GAME_MODE -> GlobalStateReplayCache.Key.GAME_MODE;
                case START_RAINING -> GlobalStateReplayCache.Key.RAIN_START;
                case STOP_RAINING -> GlobalStateReplayCache.Key.RAIN_STOP;
                case RAIN_LEVEL_CHANGE -> GlobalStateReplayCache.Key.RAIN_LEVEL;
                case THUNDER_LEVEL_CHANGE -> GlobalStateReplayCache.Key.THUNDER_LEVEL;
                default -> null;
            };
            if (key != null) cacheGlobal(key, event);
            return packet;
        }
        if (packet instanceof ClientboundSetDefaultSpawnPositionPacket spawn) {
            cacheGlobal(GlobalStateReplayCache.Key.DEFAULT_SPAWN, spawn);
            return spawn.withGlobalPos(shiftGlobalPos(spawn.getGlobalPos()));
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket center) {
            if (chunkReplayCache != null) {
                int radius = viewDistanceChunks + (config == null ? 4 : config.evictMarginChunks);
                chunkReplayCache.evictOutside(center.getChunkX(), center.getChunkZ(), radius);
            }
            return center.withChunkX(center.getChunkX() - origin.offsetX() / 16)
                    .withChunkZ(center.getChunkZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundLightUpdatePacket light) {
            cacheLightUpdate(light);
            return light.withX(light.getX() - origin.offsetX() / 16)
                    .withZ(light.getZ() - origin.offsetZ() / 16);
        }
        if (packet instanceof ClientboundBlockUpdatePacket block) {
            cacheBlockUpdate(block.getEntry());
            BlockChangeEntry entry = block.getEntry();
            Vector3i p = entry.getPosition();
            return block.withEntry(new BlockChangeEntry(
                    Vector3i.from(p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ()),
                    entry.getBlock()));
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            for (BlockChangeEntry entry : section.getEntries()) cacheBlockUpdate(entry);
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
        if (packet instanceof ClientboundTrackedWaypointPacket tracked) {
            TrackedWaypoint waypoint = tracked.getWaypoint();
            if (waypoint.data() instanceof Vec3iWaypointData point) {
                Vector3i p = point.vector();
                TrackedWaypoint shifted = new TrackedWaypoint(waypoint.uuid(), waypoint.id(), waypoint.icon(),
                        waypoint.type(), new Vec3iWaypointData(Vector3i.from(
                        p.getX() - origin.offsetX(), p.getY(), p.getZ() - origin.offsetZ())));
                return tracked.withWaypoint(shifted);
            }
            if (waypoint.data() instanceof ChunkWaypointData chunk) {
                TrackedWaypoint shifted = new TrackedWaypoint(waypoint.uuid(), waypoint.id(), waypoint.icon(),
                        waypoint.type(), new ChunkWaypointData(
                        chunk.chunkX() - origin.offsetX() / 16, chunk.chunkZ() - origin.offsetZ() / 16));
                return tracked.withWaypoint(shifted);
            }
        }
        if (packet instanceof ClientboundChunksBiomesPacket biomes) {
            cacheBiomes(biomes);
            List<ChunkBiomeData> shifted = new ArrayList<>();
            for (ChunkBiomeData entry : biomes.getChunkBiomeData()) {
                shifted.add(new ChunkBiomeData(entry.getX() - origin.offsetX() / 16,
                        entry.getZ() - origin.offsetZ() / 16, entry.getBuffer()));
            }
            return new ClientboundChunksBiomesPacket(shifted);
        }
        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            return particles.withParticle(shiftParticle(particles.getParticle()))
                    .withX(particles.getX() - origin.offsetX())
                    .withZ(particles.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundSoundPacket sound) {
            return sound.withX(sound.getX() - origin.offsetX()).withZ(sound.getZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundExplodePacket explosion) {
            Vector3d p = explosion.getCenter();
            return explosion.withCenter(Vector3d.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()))
                    .withExplosionParticle(shiftParticle(explosion.getExplosionParticle()));
        }
        if (packet instanceof ClientboundOpenSignEditorPacket sign) {
            Vector3i p = sign.getPosition();
            return sign.withPosition(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()));
        }
        if (packet instanceof ClientboundUpdateAdvancementsPacket advancements) {
            Advancement[] entries = advancements.getAdvancements().clone();
            for (int i = 0; i < entries.length; i++) {
                Advancement entry = entries[i];
                Advancement.DisplayData display = entry.getDisplayData();
                if (display != null) {
                    Advancement.DisplayData shiftedDisplay = new Advancement.DisplayData(
                            display.getTitle(), display.getDescription(), shiftItemStack(display.getIcon()),
                            display.getAdvancementType(), display.isShowToast(), display.isHidden(),
                            display.getBackgroundTexture());
                    entries[i] = new Advancement(entry.getId(), entry.getRequirements(), entry.getParentId(),
                            shiftedDisplay, entry.isSendsTelemetryEvent(), entry.getPosX(), entry.getPosY());
                }
            }
            return advancements.withAdvancements(entries);
        }
        if (packet instanceof ClientboundMerchantOffersPacket merchant) {
            List<VillagerTrade> offers = new ArrayList<>();
            for (VillagerTrade trade : merchant.getOffers()) {
                offers.add(new VillagerTrade(shiftItemCost(trade.getItemCostA()), shiftItemStack(trade.getResult()),
                        shiftItemCost(trade.getItemCostB()), trade.isOutOfStock(), trade.getUses(), trade.getMaxUses(),
                        trade.getXp(), trade.getSpecialPriceDiff(), trade.getPriceMultiplier(), trade.getDemand()));
            }
            return merchant.withOffers(offers);
        }
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            cacheEntity(equipment.getEntityId(), EntityReplayCache.Kind.EQUIPMENT, 0, equipment);
            Equipment[] entries = equipment.getEquipment().clone();
            for (int i = 0; i < entries.length; i++) {
                Equipment entry = entries[i];
                entries[i] = new Equipment(entry.getSlot(), shiftItemStack(entry.getItem()));
            }
            return equipment.withEquipment(entries);
        }
        if (packet instanceof ClientboundContainerSetContentPacket content) {
            ItemStack[] items = content.getItems() == null ? null : content.getItems().clone();
            if (items != null) {
                for (int i = 0; i < items.length; i++) items[i] = shiftItemStack(items[i]);
            }
            return content.withItems(items).withCarriedItem(shiftItemStack(content.getCarriedItem()));
        }
        if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            return slot.withItem(shiftItemStack(slot.getItem()));
        }
        if (packet instanceof ClientboundSetCursorItemPacket cursor) {
            return cursor.withContents(shiftItemStack(cursor.getContents()));
        }
        if (packet instanceof ClientboundSetPlayerInventoryPacket inventory) {
            return inventory.withContents(shiftItemStack(inventory.getContents()));
        }
        if (packet instanceof ClientboundSetEntityDataPacket metadata) {
            if (metadata.getMetadata() != null) {
                for (EntityMetadata<?, ?> entry : metadata.getMetadata()) {
                    ClientboundSetEntityDataPacket single =
                            new ClientboundSetEntityDataPacket(metadata.getEntityId(), new EntityMetadata[]{entry});
                    cacheEntity(metadata.getEntityId(), EntityReplayCache.Kind.METADATA, entry.getId(), single);
                }
            }
            return metadata.withMetadata(shiftMetadata(metadata.getMetadata()));
        }
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
            cacheBlockEntity(blockEntity);
            Vector3i p = blockEntity.getPosition();
            return blockEntity.withPosition(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                    p.getZ() - origin.offsetZ()))
                    .withNbt(NbtShift.shift(blockEntity.getNbt(), origin.offsetX(), origin.offsetZ(), config));
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
            cacheGlobal(GlobalStateReplayCache.Key.BORDER_INITIALIZE, border);
            return border.withNewCenterX(border.getNewCenterX() - origin.offsetX())
                    .withNewCenterZ(border.getNewCenterZ() - origin.offsetZ());
        }
        if (packet instanceof ClientboundSetBorderLerpSizePacket border) {
            cacheGlobal(GlobalStateReplayCache.Key.BORDER_LERP_SIZE, border);
            return packet;
        }
        if (packet instanceof ClientboundSetBorderSizePacket border) {
            cacheGlobal(GlobalStateReplayCache.Key.BORDER_SIZE, border);
            return packet;
        }
        if (packet instanceof ClientboundSetBorderCenterPacket border) {
            cacheGlobal(GlobalStateReplayCache.Key.BORDER_CENTER, border);
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
        return serverbound(packet, true, false);
    }

    Packet serverbound(Packet packet, boolean allowOriginShift) {
        return serverbound(packet, allowOriginShift, false);
    }

    Packet serverbound(Packet packet, boolean allowOriginShift, boolean relaxSafetyGuard) {
        double jumpLimit = relaxSafetyGuard ? transitionGraceMaxJump() : maxServerboundJump;
        return serverbound(packet, allowOriginShift, jumpLimit);
    }

    Packet serverbound(Packet packet, boolean allowOriginShift, double jumpLimit) {
        pendingMovementRejection = null;
        if (packet instanceof ServerboundAcceptTeleportationPacket accept) {
            // Geyser includes its Bedrock-confirmed position in this packet.
            // Restore the floating-origin offset just like ordinary movement.
            return accept.withX(accept.getX() + origin.offsetX())
                    .withZ(accept.getZ() + origin.offsetZ());
        }
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
        if (packet instanceof ServerboundSetTestBlockPacket testBlock) {
            Vector3i p = testBlock.getPosition();
            return testBlock.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundSetCommandBlockPacket command) {
            Vector3i p = command.getPosition();
            return command.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundSetStructureBlockPacket structure) {
            Vector3i p = structure.getPosition();
            return structure.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundSetJigsawBlockPacket jigsaw) {
            Vector3i p = jigsaw.getPosition();
            return jigsaw.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundSignUpdatePacket sign) {
            Vector3i p = sign.getPosition();
            return sign.withPosition(Vector3i.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundSetCreativeModeSlotPacket creative) {
            return creative.withClickedItem(shiftItemStack(creative.getClickedItem()));
        }
        if (packet instanceof ServerboundMoveVehiclePacket move) {
            Vector3d p = move.getPosition();
            double realX = p.getX() + origin.offsetX();
            double realZ = p.getZ() + origin.offsetZ();
            lastPlayerY = p.getY();
            if (!acceptMovementPosition(realX, realZ, allowOriginShift, jumpLimit)) return null;
            return move.withPosition(Vector3d.from(realX, p.getY(), realZ));
        }
        if (packet instanceof ServerboundMovePlayerPosPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            lastPlayerY = move.getY();
            hasPlayerPosition = true;
            if (!acceptMovementPosition(realX, realZ, allowOriginShift, jumpLimit)) return null;
            return move.withX(realX).withZ(realZ);
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            lastPlayerY = move.getY();
            lastPlayerYaw = move.getYaw();
            lastPlayerPitch = move.getPitch();
            hasPlayerPosition = true;
            if (!acceptMovementPosition(realX, realZ, allowOriginShift, jumpLimit)) return null;
            return move.withX(realX).withZ(realZ);
        }
        return packet;
    }

    boolean observeMovementDuringReplay(Packet packet) {
        return observeMovementDuringReplay(packet, maxServerboundJump);
    }

    boolean observeMovementDuringReplay(Packet packet, double jumpLimit) {
        pendingMovementRejection = null;
        if (packet instanceof ServerboundMoveVehiclePacket move) {
            Vector3d position = move.getPosition();
            double realX = position.getX() + origin.offsetX();
            double realZ = position.getZ() + origin.offsetZ();
            if (!acceptMovementPosition(realX, realZ, false, jumpLimit)) return false;
            lastPlayerY = position.getY();
            return true;
        }
        if (packet instanceof ServerboundMovePlayerPosPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            if (!acceptMovementPosition(realX, realZ, false, jumpLimit)) return false;
            lastPlayerY = move.getY();
            hasPlayerPosition = true;
            return true;
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            if (!acceptMovementPosition(realX, realZ, false, jumpLimit)) return false;
            lastPlayerY = move.getY();
            lastPlayerYaw = move.getYaw();
            lastPlayerPitch = move.getPitch();
            hasPlayerPosition = true;
            return true;
        }
        if (packet instanceof ServerboundMovePlayerRotPacket move) {
            lastPlayerYaw = move.getYaw();
            lastPlayerPitch = move.getPitch();
            return true;
        }
        return false;
    }

    MovementRejection takeMovementRejection() {
        MovementRejection rejection = pendingMovementRejection;
        pendingMovementRejection = null;
        return rejection;
    }

    double maxServerboundJump() {
        return maxServerboundJump;
    }

    double transitionGraceMaxJump() {
        return config == null ? Math.max(256.0, maxServerboundJump)
                : Math.max(config.transitionGraceMaxJump, maxServerboundJump);
    }

    private boolean acceptMovementPosition(double realX, double realZ, boolean allowOriginShift, double jumpLimit) {
        pendingMovementRejection = null;
        double distance = origin.distanceFromLastPosition(realX, realZ);
        int chunkX = (int) Math.floor(realX / 16.0);
        int chunkZ = (int) Math.floor(realZ / 16.0);
        boolean replayCacheAvailable = chunkReplayCache != null
                && !chunkReplayCache.snapshotWithinRadius(chunkX, chunkZ, 1, 1).isEmpty();
        if (!origin.acceptServerboundPosition(realX, realZ, jumpLimit, replayCacheAvailable, allowOriginShift)) {
            pendingMovementRejection = new MovementRejection(distance, jumpLimit);
            return false;
        }
        JavaOriginState.Snapshot movementChange = origin.takeMovementOriginChange();
        if (movementChange != null && movementChange.changed()) {
            pendingOriginChange = movementChange;
            return false;
        }
        return true;
    }

    boolean takeDeathPositionWarning() {
        boolean warning = pendingDeathPositionWarning;
        pendingDeathPositionWarning = false;
        return warning;
    }

    DeathPositionUpdate takeDeathPositionUpdate() {
        if (!deathPositionUpdatePending) return null;
        deathPositionUpdatePending = false;
        return new DeathPositionUpdate(pendingDeathPositionUpdate);
    }

    ClientboundPlayerPositionPacket syntheticPlayerPosition(int reservedTeleportId) {
        // The origin state is authoritative for whether a real position is known.
        // Serverbound movement can establish it before any clientbound absolute
        // position packet has populated the transform's presentation flag.
        if (!origin.hasPosition()) return null;
        hasPlayerPosition = true;
        return new ClientboundPlayerPositionPacket(reservedTeleportId,
                origin.realX() - origin.offsetX(), lastPlayerY,
                origin.realZ() - origin.offsetZ(), 0, 0, 0, lastPlayerYaw, lastPlayerPitch);
    }

    double realX() { return origin.realX(); }
    double realZ() { return origin.realZ(); }

    long liveUpdateBudgetBytes() {
        return liveUpdateBudgetBytes;
    }

    int replayBatchSize() {
        return config == null ? 24 : Math.max(1, config.resendPerTick);
    }

    int entityReplayBatchSize() {
        return Math.max(32, replayBatchSize() * 8);
    }

    double entityReplayRadius() {
        return Math.max(256.0, viewDistanceChunks * 16.0 + 128.0);
    }

    int replayTimeoutMillis(int chunks, int entities) {
        int chunkBatches = (chunks + replayBatchSize() - 1) / replayBatchSize();
        int entityBatches = (entities + entityReplayBatchSize() - 1) / entityReplayBatchSize();
        long estimate = (long) (chunkBatches + entityBatches + 4) * 50L * 3L + 15_000L;
        return (int) Math.min(120_000L, Math.max(30_000L, estimate));
    }

    int transitionCooldownTicks() { return config.transitionCooldownTicks; }
    int transitionGraceTicks() { return config.transitionGraceTicks; }
    

    TransitionWalkFallback.Action deferredWalkFallback(Packet packet) {
        double localX;
        double localZ;
        if (packet instanceof ServerboundMovePlayerPosPacket move) {
            localX = move.getX();
            localZ = move.getZ();
        } else if (packet instanceof ServerboundMovePlayerPosRotPacket move) {
            localX = move.getX();
            localZ = move.getZ();
        } else if (packet instanceof ServerboundMoveVehiclePacket move) {
            localX = move.getPosition().getX();
            localZ = move.getPosition().getZ();
        } else {
            return TransitionWalkFallback.Action.DEFER;
        }
        return TransitionWalkFallback.decide(config.walkFallback, localX, localZ, origin.triggerDistance());
    }

    boolean forceDeferredReorigin(Packet packet) {
        double realX;
        double realZ;
        if (packet instanceof ServerboundMovePlayerPosPacket move) {
            realX = move.getX() + origin.offsetX();
            realZ = move.getZ() + origin.offsetZ();
        } else if (packet instanceof ServerboundMovePlayerPosRotPacket move) {
            realX = move.getX() + origin.offsetX();
            realZ = move.getZ() + origin.offsetZ();
        } else if (packet instanceof ServerboundMoveVehiclePacket move) {
            realX = move.getPosition().getX() + origin.offsetX();
            realZ = move.getPosition().getZ() + origin.offsetZ();
        } else {
            return false;
        }
        int chunkX = (int) Math.floor(realX / 16.0);
        int chunkZ = (int) Math.floor(realZ / 16.0);
        if (chunkReplayCache == null
                || chunkReplayCache.snapshotWithinRadius(chunkX, chunkZ, 1, 1).isEmpty()) return false;
        return origin.forceMovementOrigin(realX, realZ).changed();
    }

    int transitionTimeoutMillis() {
        return config == null ? 5000 : Math.max(1, config.transitionTimeoutTicks) * 50;
    }

    int syntheticAckTimeoutMillis() {
        return config == null ? 3000 : Math.max(250, config.ackTimeoutMs);
    }

    /**
     * Creates the two respawns needed to force Geyser to clear same-dimension
     * world caches: one temporary world name, then the original world name.
     * These packets must be injected directly into the inbound pipeline, bypassing
     * this transform so the real-world identity and cache remain untouched.
     */
    List<Packet> syntheticResetRespawns() {
        if (currentSpawnInfo == null || currentWorld == null) return List.of();
        Key temporaryWorld = Key.key(currentWorld.namespace(), currentWorld.value() + "_realcoords_reset");
        PlayerSpawnInfo localSpawn = new PlayerSpawnInfo(currentSpawnInfo.getDimension(), currentWorld,
                currentSpawnInfo.getHashedSeed(), currentSpawnInfo.getGameMode(),
                currentSpawnInfo.getPreviousGamemode(), currentSpawnInfo.isDebug(), currentSpawnInfo.isFlat(),
                shiftGlobalPos(currentSpawnInfo.getLastDeathPos()), currentSpawnInfo.getPortalCooldown(),
                currentSpawnInfo.getSeaLevel());
        PlayerSpawnInfo temporarySpawn = new PlayerSpawnInfo(localSpawn.getDimension(), temporaryWorld,
                localSpawn.getHashedSeed(), localSpawn.getGameMode(), localSpawn.getPreviousGamemode(),
                localSpawn.isDebug(), localSpawn.isFlat(), localSpawn.getLastDeathPos(),
                localSpawn.getPortalCooldown(), localSpawn.getSeaLevel());
        return List.of(
                new ClientboundRespawnPacket(temporarySpawn, true, true),
                new ClientboundRespawnPacket(localSpawn, true, true));
    }

    int cachedEntityCount() {
        return entityReplayCache == null ? 0 : entityReplayCache.size();
    }

    List<Packet> replayGlobalState() {
        if (globalStateReplayCache == null) return List.of();
        return replayGlobalBatch(globalStateReplayCache.snapshot());
    }

    private void cacheGlobal(GlobalStateReplayCache.Key key, MinecraftPacket packet) {
        if (globalStateReplayCache == null || replayingCachedPackets) return;
        try {
            globalStateReplayCache.put(key, packet.getClass(), JavaPacketSnapshot.serialize(packet));
        } catch (RuntimeException exception) {
            // Cache failures must not interrupt the live packet stream.
        }
    }

    List<Packet> replayCachedEntities(double centerX, double centerZ, double radiusBlocks, int limit) {
        if (entityReplayCache == null || limit <= 0) return List.of();
        return replayEntityBatch(entityReplayCache.snapshotNearestFirst(centerX, centerZ, radiusBlocks, limit));
    }

    private void cacheEntityPosition(int entityId) {
        if (entityReplayCache == null || replayingCachedPackets) return;
        EntityReplayCache.Position position = entityReplayCache.position(entityId);
        if (position == null) return;
        ClientboundEntityPositionSyncPacket absolute = new ClientboundEntityPositionSyncPacket(
                entityId, false, Vector3d.from(position.x(), position.y(), position.z()), null,
                position.yaw(), position.pitch(), position.onGround());
        cacheEntity(entityId, EntityReplayCache.Kind.POSITION, 0, absolute);
    }

    private void cacheEntity(int entityId, EntityReplayCache.Kind kind, int subKey, MinecraftPacket packet) {
        if (entityReplayCache == null || replayingCachedPackets) return;
        try {
            entityReplayCache.put(entityId, kind, subKey, packet.getClass(), JavaPacketSnapshot.serialize(packet));
        } catch (RuntimeException exception) {
            // Cache failures must not interrupt the live packet stream.
        }
    }

    int cachedChunkCount() {
        return chunkReplayCache == null ? 0 : chunkReplayCache.size();
    }

    List<ChunkReplayCache.ReplayChunk> cachedChunks(int centerChunkX, int centerChunkZ, int limit) {
        return chunkReplayCache == null ? List.of()
                : chunkReplayCache.snapshotNearestFirst(centerChunkX, centerChunkZ, limit);
    }

    private boolean cacheActive() {
        // Replay packets are already snapshots; never feed them back into the cache.
        return chunkReplayCache != null && !replayingCachedPackets;
    }

    /**
     * Rebuilds a bounded batch of cached chunks in the current local coordinate
     * system. Each base chunk is followed by its captured block/light/biome overlays.
     * The caller must inject the returned packets into Geyser's inbound pipeline on
     * the downstream channel event loop.
     */
    List<Packet> replayCachedChunks(int centerChunkX, int centerChunkZ, int limit) {
        return replayCachedChunks(centerChunkX, centerChunkZ, 0, limit);
    }

    record ReplaySnapshot(List<ChunkReplayCache.ReplayChunk> chunks,
                          List<EntityReplayCache.ReplayPacket> entities,
                          List<GlobalStateReplayCache.Snapshot> globalState) {
        ReplaySnapshot {
            chunks = List.copyOf(chunks);
            entities = List.copyOf(entities);
            globalState = List.copyOf(globalState);
        }
    }

    ReplaySnapshot prepareReplaySnapshot(int centerChunkX, int centerChunkZ,
                                         double centerX, double centerZ, double entityRadius) {
        int chunkRadius = viewDistanceChunks + (config == null ? 4 : config.evictMarginChunks);
        List<ChunkReplayCache.ReplayChunk> chunks = chunkReplayCache == null ? List.of()
                : chunkReplayCache.snapshotWithinRadius(centerChunkX, centerChunkZ, chunkRadius,
                        chunkReplayCache.size());
        List<EntityReplayCache.ReplayPacket> entities = entityReplayCache == null ? List.of()
                : entityReplayCache.snapshotNearestFirst(centerX, centerZ, entityRadius, 512);
        List<GlobalStateReplayCache.Snapshot> global = globalStateReplayCache == null ? List.of()
                : globalStateReplayCache.snapshot();
        return new ReplaySnapshot(chunks, entities, global);
    }

    List<Packet> replayChunkBatch(List<ChunkReplayCache.ReplayChunk> chunks) {
        if (chunks.isEmpty()) return List.of();
        List<Packet> replay = new ArrayList<>();
        replayingCachedPackets = true;
        try {
            for (ChunkReplayCache.ReplayChunk chunk : chunks) {
                ClientboundLevelChunkWithLightPacket base = JavaPacketSnapshot.deserialize(
                        ClientboundLevelChunkWithLightPacket.class, chunk.payload());
                replay.add(clientbound(base));
                for (ChunkReplayCache.ReplayOverlay overlay : chunk.overlays()) {
                    MinecraftPacket update = switch (overlay.type()) {
                        case BLOCK_UPDATE -> JavaPacketSnapshot.deserialize(
                                ClientboundBlockUpdatePacket.class, overlay.payload());
                        case BLOCK_ENTITY -> JavaPacketSnapshot.deserialize(
                                ClientboundBlockEntityDataPacket.class, overlay.payload());
                        case LIGHT_UPDATE -> JavaPacketSnapshot.deserialize(
                                ClientboundLightUpdatePacket.class, overlay.payload());
                        case CHUNK_BIOMES -> JavaPacketSnapshot.deserialize(
                                ClientboundChunksBiomesPacket.class, overlay.payload());
                    };
                    replay.add(clientbound(update));
                }
            }
        } finally {
            replayingCachedPackets = false;
        }
        return List.copyOf(replay);
    }

    List<Packet> replayEntityBatch(List<EntityReplayCache.ReplayPacket> entities) {
        if (entities.isEmpty()) return List.of();
        List<Packet> replay = new ArrayList<>(entities.size());
        replayingCachedPackets = true;
        try {
            for (EntityReplayCache.ReplayPacket cached : entities) {
                MinecraftPacket packet = JavaPacketSnapshot.deserialize(cached.packetType(), cached.payload());
                replay.add(clientbound(packet));
            }
        } finally {
            replayingCachedPackets = false;
        }
        return List.copyOf(replay);
    }

    List<Packet> replayBufferedUpdates(List<LiveUpdateBuffer.BufferedPacket> updates) {
        if (updates.isEmpty()) return List.of();
        List<Packet> replay = new ArrayList<>(updates.size());
        replayingCachedPackets = true;
        try {
            for (LiveUpdateBuffer.BufferedPacket buffered : updates) {
                MinecraftPacket packet = JavaPacketSnapshot.deserialize(buffered.packetType(), buffered.payload());
                replay.add(clientbound(packet));
            }
        } finally {
            replayingCachedPackets = false;
        }
        return List.copyOf(replay);
    }

    List<Packet> replayGlobalBatch(List<GlobalStateReplayCache.Snapshot> states) {
        if (states.isEmpty()) return List.of();
        List<Packet> replay = new ArrayList<>(states.size());
        replayingCachedPackets = true;
        try {
            for (GlobalStateReplayCache.Snapshot snapshot : states) {
                MinecraftPacket packet = JavaPacketSnapshot.deserialize(snapshot.packetType(), snapshot.payload());
                replay.add(clientbound(packet));
            }
        } finally {
            replayingCachedPackets = false;
        }
        return List.copyOf(replay);
    }

    List<Packet> replayCachedChunks(int centerChunkX, int centerChunkZ, int startIndex, int limit) {
        if (chunkReplayCache == null || limit <= 0 || startIndex < 0) return List.of();
        return replayChunkBatch(chunkReplayCache.snapshotNearestFirst(centerChunkX, centerChunkZ, startIndex, limit));
    }

    private void cacheChunk(ClientboundLevelChunkWithLightPacket chunk) {
        if (!cacheActive()) return;
        try {
            chunkReplayCache.put(chunk.getX(), chunk.getZ(), JavaPacketSnapshot.serialize(chunk));
        } catch (RuntimeException exception) {
            // Cache failure must never interrupt the live packet stream.
        }
    }

    private void cacheBlockUpdate(BlockChangeEntry entry) {
        if (!cacheActive()) return;
        Vector3i position = entry.getPosition();
        ClientboundBlockUpdatePacket snapshot = new ClientboundBlockUpdatePacket(entry);
        cacheOverlay(position, ChunkReplayCache.OverlayType.BLOCK_UPDATE, snapshot);
    }

    private void cacheBlockEntity(ClientboundBlockEntityDataPacket packet) {
        if (!cacheActive()) return;
        cacheOverlay(packet.getPosition(), ChunkReplayCache.OverlayType.BLOCK_ENTITY, packet);
    }

    private void cacheBiomes(ClientboundChunksBiomesPacket packet) {
        if (!cacheActive()) return;
        for (ChunkBiomeData entry : packet.getChunkBiomeData()) {
            try {
                ClientboundChunksBiomesPacket single = new ClientboundChunksBiomesPacket(List.of(entry));
                ChunkReplayCache.BlockPos chunkPosition =
                        new ChunkReplayCache.BlockPos(entry.getX() << 4, 0, entry.getZ() << 4);
                chunkReplayCache.putOverlay(entry.getX(), entry.getZ(),
                        ChunkReplayCache.OverlayType.CHUNK_BIOMES, chunkPosition,
                        JavaPacketSnapshot.serialize(single));
            } catch (RuntimeException exception) {
                // Cache failure must never interrupt the live packet stream.
            }
        }
    }

    private void cacheLightUpdate(ClientboundLightUpdatePacket packet) {
        if (!cacheActive()) return;
        ChunkReplayCache.BlockPos chunkPosition =
                new ChunkReplayCache.BlockPos(packet.getX() << 4, 0, packet.getZ() << 4);
        try {
            chunkReplayCache.putOverlay(packet.getX(), packet.getZ(),
                    ChunkReplayCache.OverlayType.LIGHT_UPDATE, chunkPosition,
                    JavaPacketSnapshot.serialize(packet));
        } catch (RuntimeException exception) {
            // Cache failure must never interrupt the live packet stream.
        }
    }

    private void cacheOverlay(Vector3i position, ChunkReplayCache.OverlayType type, MinecraftPacket packet) {
        try {
            chunkReplayCache.putOverlay(position.getX() >> 4, position.getZ() >> 4, type,
                    new ChunkReplayCache.BlockPos(position.getX(), position.getY(), position.getZ()),
                    JavaPacketSnapshot.serialize(packet));
        } catch (RuntimeException exception) {
            // Cache failure must never interrupt the live packet stream.
        }
    }

    JavaOriginState.Snapshot takeOriginChange() {
        JavaOriginState.Snapshot snapshot = pendingOriginChange;
        pendingOriginChange = null;
        return snapshot;
    }

    int offsetX() {
        return origin.offsetX();
    }

    int offsetZ() {
        return origin.offsetZ();
    }

    private ItemStack shiftItemStack(ItemStack item) {
        if (item == null || item.getDataComponentsPatch() == null) return item;
        DataComponents shifted = shiftComponents(item.getDataComponentsPatch());
        return shifted == item.getDataComponentsPatch()
                ? item : new ItemStack(item.getId(), item.getAmount(), shifted);
    }

    private DataComponents shiftComponents(DataComponents components) {
        LodestoneTracker tracker = components.get(DataComponentTypes.LODESTONE_TRACKER);
        if (tracker == null || tracker.getPos() == null) return components;
        DataComponents shifted = components.clone();
        shifted.put(DataComponentTypes.LODESTONE_TRACKER, tracker.withPos(shiftGlobalPos(tracker.getPos())));
        return shifted;
    }

    private VillagerTrade.ItemCost shiftItemCost(VillagerTrade.ItemCost cost) {
        if (cost == null || cost.components() == null || cost.components().isEmpty()) return cost;
        DataComponents components = new DataComponents(new java.util.HashMap<>(cost.components()));
        DataComponents shifted = shiftComponents(components);
        if (shifted == components) return cost;
        return new VillagerTrade.ItemCost(cost.itemId(), cost.count(), shifted.getDataComponents());
    }

    private Particle shiftParticle(Particle particle) {
        if (particle.getData() instanceof VibrationParticleData vibration
                && vibration.getPositionSource() instanceof BlockPositionSource block) {
            Vector3i p = block.getPosition();
            VibrationParticleData shifted = new VibrationParticleData(
                    new BlockPositionSource(Vector3i.from(p.getX() - origin.offsetX(), p.getY(),
                            p.getZ() - origin.offsetZ())), vibration.getArrivalTicks());
            return new Particle(particle.getType(), shifted);
        }
        return particle;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private EntityMetadata<?, ?>[] shiftMetadata(EntityMetadata<?, ?>[] metadata) {
        if (metadata == null || (origin.offsetX() == 0 && origin.offsetZ() == 0)) return metadata;
        EntityMetadata<?, ?>[] shifted = metadata.clone();
        for (int i = 0; i < shifted.length; i++) {
            EntityMetadata<?, ?> entry = shifted[i];
            Object value = entry.getValue();
            Object replacement = value;
            if (value instanceof Vector3i position) {
                replacement = Vector3i.from(position.getX() - origin.offsetX(), position.getY(),
                        position.getZ() - origin.offsetZ());
            } else if (value instanceof ItemStack item) {
                replacement = shiftItemStack(item);
            } else if (value instanceof GlobalPos globalPos) {
                replacement = shiftGlobalPos(globalPos);
            } else if (value instanceof java.util.Optional<?> optional && optional.isPresent()) {
                Object nested = optional.get();
                if (nested instanceof Vector3i position) {
                    replacement = java.util.Optional.of(Vector3i.from(position.getX() - origin.offsetX(),
                            position.getY(), position.getZ() - origin.offsetZ()));
                } else if (nested instanceof GlobalPos globalPos) {
                    replacement = java.util.Optional.of(shiftGlobalPos(globalPos));
                }
            }
            if (replacement != value) {
                shifted[i] = new ObjectEntityMetadata(entry.getId(), (MetadataType) entry.getType(), replacement);
            }
        }
        return shifted;
    }

    private PlayerSpawnInfo shiftSpawnInfo(PlayerSpawnInfo info) {
        // The origin is selected by the following player-position packet. Keep the
        // original GlobalPos here; the hook refreshes Geyser's stored metadata after
        // the new origin has been selected.
        return new PlayerSpawnInfo(info.getDimension(), info.getWorldName(), info.getHashedSeed(),
                info.getGameMode(), info.getPreviousGamemode(), info.isDebug(), info.isFlat(),
                info.getLastDeathPos(), info.getPortalCooldown(), info.getSeaLevel());
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
