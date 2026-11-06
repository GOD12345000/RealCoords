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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateAdvancementsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
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
    private final JavaOriginState origin;
    private int viewDistanceChunks = 10;
    private final double maxServerboundJump;
    private final ShiftConfig config;
    private final ChunkReplayCache chunkReplayCache;
    private boolean replayingCachedPackets;
    private Key currentWorld;
    private JavaOriginState.Snapshot pendingOriginChange;
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
        this.chunkReplayCache = config != null && config.cacheEnabled
                ? new ChunkReplayCache(config.cacheMaxChunks, config.cacheMaxBytes, config.cacheCompress) : null;
    }

    Packet clientbound(Packet packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            if (chunkReplayCache != null) chunkReplayCache.clear();
            origin.login();
            currentWorld = login.getCommonPlayerSpawnInfo().getWorldName();
            lastDeathPosition = login.getCommonPlayerSpawnInfo().getLastDeathPos();
            hasLastDeathPosition = lastDeathPosition != null;
            origin.respawn(dimensionId(currentWorld.asString()));
            viewDistanceChunks = login.getViewDistance();
            return login.withCommonPlayerSpawnInfo(shiftSpawnInfo(login.getCommonPlayerSpawnInfo()));
        }
        if (packet instanceof ClientboundRespawnPacket respawn) {
            if (chunkReplayCache != null) chunkReplayCache.clear();
            currentWorld = respawn.getCommonPlayerSpawnInfo().getWorldName();
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
            if (lookAt.getTargetEntityOrigin() != null) return packet;
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
        if (packet instanceof ClientboundSetDefaultSpawnPositionPacket spawn) {
            return spawn.withGlobalPos(shiftGlobalPos(spawn.getGlobalPos()));
        }
        if (packet instanceof ClientboundSetChunkCacheCenterPacket center) {
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
            return move.withPosition(Vector3d.from(p.getX() + origin.offsetX(), p.getY(),
                    p.getZ() + origin.offsetZ()));
        }
        if (packet instanceof ServerboundMovePlayerPosPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            if (!origin.acceptServerboundPosition(realX, realZ, maxServerboundJump)) return null;
            return move.withX(realX).withZ(realZ);
        }
        if (packet instanceof ServerboundMovePlayerPosRotPacket move) {
            double realX = move.getX() + origin.offsetX();
            double realZ = move.getZ() + origin.offsetZ();
            if (!origin.acceptServerboundPosition(realX, realZ, maxServerboundJump)) return null;
            return move.withX(realX).withZ(realZ);
        }
        return packet;
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
        if (chunkReplayCache == null || limit <= 0) return List.of();
        List<Packet> replay = new ArrayList<>();
        replayingCachedPackets = true;
        try {
            for (ChunkReplayCache.ReplayChunk chunk :
                    chunkReplayCache.snapshotNearestFirst(centerChunkX, centerChunkZ, limit)) {
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
