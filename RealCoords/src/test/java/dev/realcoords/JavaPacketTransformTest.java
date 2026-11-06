package dev.realcoords;

import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.LodestoneTracker;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.ChunkWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.TrackedWaypoint;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.Vec3iWaypointData;
import org.geysermc.mcprotocollib.protocol.data.game.level.waypoint.WaypointOperation;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RainStrengthValue;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JavaPacketTransformTest {
    private static JavaPacketTransform transformer() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        return new JavaPacketTransform(config);
    }

    @Test
    void syntheticResetUsesTemporaryThenOriginalWorldNameAndLocalDeathPosition() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        GlobalPos death = new GlobalPos(world, Vector3i.from(22_999_000, 64, -17_001_000));
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 123L, GameMode.SURVIVAL,
                null, false, false, death, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));

        List<Packet> reset = transform.syntheticResetRespawns();
        assertEquals(2, reset.size());
        ClientboundRespawnPacket temporary = assertInstanceOf(ClientboundRespawnPacket.class, reset.get(0));
        ClientboundRespawnPacket restored = assertInstanceOf(ClientboundRespawnPacket.class, reset.get(1));
        assertEquals("minecraft:overworld_realcoords_reset",
                temporary.getCommonPlayerSpawnInfo().getWorldName().asString());
        assertEquals(world, restored.getCommonPlayerSpawnInfo().getWorldName());
        assertEquals(temporary.getCommonPlayerSpawnInfo().getDimension(),
                restored.getCommonPlayerSpawnInfo().getDimension());
        Vector3i shiftedDeath = restored.getCommonPlayerSpawnInfo().getLastDeathPos().getPosition();
        assertEquals(death.getPosition().getX() - transform.offsetX(), shiftedDeath.getX());
        assertEquals(death.getPosition().getZ() - transform.offsetZ(), shiftedDeath.getZ());
        assertTrue(temporary.isKeepMetadata());
        assertTrue(temporary.isKeepAttributeModifiers());
        assertTrue(restored.isKeepMetadata());
        assertTrue(restored.isKeepAttributeModifiers());
    }

    @Test
    void packetSnapshotRoundTripsThroughMcProtocolLibCodec() {
        ClientboundBlockUpdatePacket original = new ClientboundBlockUpdatePacket(
                new BlockChangeEntry(Vector3i.from(123456, 72, -654321), 987));
        byte[] snapshot = JavaPacketSnapshot.serialize(original);
        ClientboundBlockUpdatePacket restored =
                JavaPacketSnapshot.deserialize(ClientboundBlockUpdatePacket.class, snapshot);

        assertEquals(original.getEntry().getPosition(), restored.getEntry().getPosition());
        assertEquals(original.getEntry().getBlock(), restored.getEntry().getBlock());
    }

    @Test
    void cachedChunkReplayRestoresBaseThenShiftedBlockOverlayWithoutRecaching() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        config.cacheEnabled = true;
        config.cacheCompress = false;
        JavaPacketTransform transform = new JavaPacketTransform(config);

        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{Key.key("minecraft:overworld")},
                20, 10, 10, false, true, false,
                new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 0L, GameMode.SURVIVAL,
                        null, false, false, null, 0, 63), true, true));
        transform.clientbound(initial);

        int realChunkX = 23_000_000 / 16;
        int realChunkZ = -17_000_000 / 16;
        ClientboundLevelChunkWithLightPacket base = new ClientboundLevelChunkWithLightPacket(
                realChunkX, realChunkZ, new byte[]{1, 2, 3}, Map.of(), new
                org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[0],
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()));
        transform.clientbound(base);

        Vector3i realBlock = Vector3i.from(23_000_004, 65, -16_999_996);
        transform.clientbound(new ClientboundBlockUpdatePacket(new BlockChangeEntry(realBlock, 123)));

        List<Packet> replay = transform.replayCachedChunks(realChunkX, realChunkZ, 1);
        assertEquals(2, replay.size());
        ClientboundLevelChunkWithLightPacket replayedChunk =
                assertInstanceOf(ClientboundLevelChunkWithLightPacket.class, replay.get(0));
        ClientboundBlockUpdatePacket replayedBlock =
                assertInstanceOf(ClientboundBlockUpdatePacket.class, replay.get(1));

        assertEquals(realChunkX - transform.offsetX() / 16, replayedChunk.getX());
        assertEquals(realChunkZ - transform.offsetZ() / 16, replayedChunk.getZ());
        assertEquals(Vector3i.from(realBlock.getX() - transform.offsetX(), realBlock.getY(),
                realBlock.getZ() - transform.offsetZ()), replayedBlock.getEntry().getPosition());

        // Replaying must not mutate or duplicate the source cache.
        assertEquals(1, transform.cachedChunkCount());
        assertEquals(2, transform.replayCachedChunks(realChunkX, realChunkZ, 1).size());
    }

    @Test
    void embeddedChannelReplacesClientboundPacketAndRestoresServerboundMovement() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        EmbeddedChannel channel = new EmbeddedChannel(
                new JavaSessionHook.JavaPacketTransformHandler(transform, null));

        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        assertTrue(channel.writeInbound(initial));
        ClientboundPlayerPositionPacket local = channel.readInbound();
        assertNotSame(initial, local);
        assertTrue(Math.abs(local.getPosition().getX()) < 256);
        assertTrue(Math.abs(local.getPosition().getZ()) < 256);

        ServerboundMovePlayerPosPacket outgoing = new ServerboundMovePlayerPosPacket(
                true, false, local.getPosition().getX() + 1, 70, local.getPosition().getZ() + 1);
        assertTrue(channel.writeOutbound(outgoing));
        ServerboundMovePlayerPosPacket restored = channel.readOutbound();
        assertEquals(23_000_001, restored.getX());
        assertEquals(-16_999_999, restored.getZ());
        channel.finishAndReleaseAll();
    }

    @Test
    void embeddedChannelPassesNonCoordinatePacketsUnchanged() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new JavaSessionHook.JavaPacketTransformHandler(transformer(), null));
        ClientboundGameEventPacket packet = new ClientboundGameEventPacket(
                GameEvent.RAIN_LEVEL_CHANGE, new RainStrengthValue(0.5f));

        assertTrue(channel.writeInbound(packet));
        assertSame(packet, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void embeddedChannelPassesNonPacketMessagesUnchanged() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new JavaSessionHook.JavaPacketTransformHandler(transformer(), null));
        Object message = new Object();

        assertTrue(channel.writeInbound(message));
        assertSame(message, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void absolutePlayerPositionSelectsOriginBeforeTransforming() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket packet = new ClientboundPlayerPositionPacket(
                7, 23_000_000, 80, -17_000_000, 0, 0, 0, 0, 0);

        ClientboundPlayerPositionPacket shifted =
                (ClientboundPlayerPositionPacket) transform.clientbound(packet);

        assertTrue(Math.abs(shifted.getPosition().getX()) < 256);
        assertTrue(Math.abs(shifted.getPosition().getZ()) < 256);
        assertEquals(0, shifted.getPosition().getY() - packet.getPosition().getY());
        assertEquals(23_000_000 - shifted.getPosition().getX(), transform.offsetX());
        assertEquals(-17_000_000 - shifted.getPosition().getZ(), transform.offsetZ());
    }

    @Test
    void relativePositionComponentsAreNotShifted() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, 17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        ClientboundPlayerPositionPacket relative = new ClientboundPlayerPositionPacket(
                2, 12.5, 70, -4.25, 0, 0, 0, 0, 0,
                PositionElement.X, PositionElement.Z);
        ClientboundPlayerPositionPacket result =
                (ClientboundPlayerPositionPacket) transform.clientbound(relative);

        assertEquals(12.5, result.getPosition().getX());
        assertEquals(-4.25, result.getPosition().getZ());
    }

    @Test
    void mixedRelativePositionComponentsOnlyShiftAbsoluteAxis() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, 17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        ClientboundPlayerPositionPacket mixed = new ClientboundPlayerPositionPacket(
                2, 12.5, 70, 17_000_100, 0, 0, 0, 0, 0, PositionElement.X);
        ClientboundPlayerPositionPacket shifted =
                (ClientboundPlayerPositionPacket) transform.clientbound(mixed);

        assertEquals(12.5, shifted.getPosition().getX());
        assertEquals(17_000_100 - transform.offsetZ(), shifted.getPosition().getZ());
    }

    @Test
    void serverboundMovementRestoresRealCoordinates() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        ClientboundPlayerPositionPacket local =
                (ClientboundPlayerPositionPacket) transform.clientbound(initial);

        ServerboundMovePlayerPosPacket outgoing = new ServerboundMovePlayerPosPacket(
                true, false, local.getPosition().getX() + 3.25, 71, local.getPosition().getZ() - 2.5);
        ServerboundMovePlayerPosPacket restored =
                (ServerboundMovePlayerPosPacket) transform.serverbound(outgoing);

        assertEquals(23_000_003.25, restored.getX());
        assertEquals(-17_000_002.5, restored.getZ());
    }

    @Test
    void blockUpdateAndBlockInteractionUseInverseCoordinates() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        Vector3i realBlock = Vector3i.from(23_000_015, 64, -16_999_991);
        ClientboundBlockUpdatePacket update = new ClientboundBlockUpdatePacket(
                new BlockChangeEntry(realBlock, 42));
        ClientboundBlockUpdatePacket local =
                (ClientboundBlockUpdatePacket) transform.clientbound(update);
        Vector3i expectedLocal = Vector3i.from(realBlock.getX() - transform.offsetX(),
                realBlock.getY(), realBlock.getZ() - transform.offsetZ());
        assertEquals(expectedLocal, local.getEntry().getPosition());

        ServerboundUseItemOnPacket use = new ServerboundUseItemOnPacket(
                expectedLocal, Direction.UP, Hand.MAIN_HAND, 0.5f, 1.0f, 0.5f,
                false, false, 1);
        ServerboundUseItemOnPacket restored =
                (ServerboundUseItemOnPacket) transform.serverbound(use);
        assertEquals(realBlock, restored.getPosition());
        assertEquals(0.5f, restored.getCursorX());
        assertEquals(1.0f, restored.getCursorY());
        assertEquals(0.5f, restored.getCursorZ());
    }

    @Test
    void deathPositionIsRebasedAfterInitialOriginSelection() {
        JavaPacketTransform transform = transformer();
        Key world = Key.key("minecraft:overworld");
        GlobalPos death = new GlobalPos(world, Vector3i.from(23_000_100, 64, -16_999_900));
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, death, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));

        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));

        JavaPacketTransform.DeathPositionUpdate update = transform.takeDeathPositionUpdate();
        assertNotNull(update);
        assertEquals(Vector3i.from(23_000_100 - transform.offsetX(), 64,
                -16_999_900 - transform.offsetZ()), update.position().getPosition());
        assertFalse(transform.takeDeathPositionWarning());
    }

    @Test
    void defaultSpawnPositionIsShiftedAfterOriginSelection() {
        JavaPacketTransform transform = transformer();
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        ClientboundLoginPacket login = new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true);
        transform.clientbound(login);

        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        GlobalPos realSpawn = new GlobalPos(world, Vector3i.from(23_000_000, 64, -17_000_000));
        ClientboundSetDefaultSpawnPositionPacket packet =
                new ClientboundSetDefaultSpawnPositionPacket(realSpawn, 0, 0);
        ClientboundSetDefaultSpawnPositionPacket shifted =
                (ClientboundSetDefaultSpawnPositionPacket) transform.clientbound(packet);

        assertEquals(Vector3i.from(23_000_000 - transform.offsetX(), 64,
                        -17_000_000 - transform.offsetZ()),
                shifted.getGlobalPos().getPosition());
    }

    @Test
    void lodestoneCompassTargetIsShiftedInsideItemComponents() {
        JavaPacketTransform transform = transformer();
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));

        GlobalPos realTarget = new GlobalPos(world, Vector3i.from(23_000_100, 64, -16_999_900));
        DataComponents components = new DataComponents(new java.util.HashMap<>());
        components.put(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTracker(realTarget, true));
        ItemStack item = new ItemStack(1, 1, components);
        ClientboundSetCursorItemPacket packet = new ClientboundSetCursorItemPacket(item);

        ClientboundSetCursorItemPacket shifted =
                (ClientboundSetCursorItemPacket) transform.clientbound(packet);
        LodestoneTracker tracker = shifted.getContents().getDataComponentsPatch()
                .get(DataComponentTypes.LODESTONE_TRACKER);
        assertEquals(Vector3i.from(realTarget.getPosition().getX() - transform.offsetX(), 64,
                realTarget.getPosition().getZ() - transform.offsetZ()), tracker.getPos().getPosition());
    }

    @Test
    void blockPositionEntityMetadataIsShiftedIncludingOptionalValues() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        Vector3i real = Vector3i.from(23_000_004, 65, -16_999_996);
        EntityMetadata<?, ?>[] entries = new EntityMetadata[]{
                new ObjectEntityMetadata<>(1, MetadataTypes.BLOCK_POS, real),
                new ObjectEntityMetadata<>(2, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.of(real))
        };
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(7, entries);
        ClientboundSetEntityDataPacket shifted =
                (ClientboundSetEntityDataPacket) transform.clientbound(packet);

        Vector3i expected = Vector3i.from(real.getX() - transform.offsetX(), real.getY(),
                real.getZ() - transform.offsetZ());
        assertEquals(expected, shifted.getMetadata()[0].getValue());
        assertEquals(Optional.of(expected), shifted.getMetadata()[1].getValue());
    }

    @Test
    void trackedWaypointBlockAndChunkPositionsAreShifted() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);
        TrackedWaypoint.Icon icon = new TrackedWaypoint.Icon(
                Key.key("minecraft:default"), Optional.empty());

        TrackedWaypoint blockWaypoint = new TrackedWaypoint(UUID.randomUUID(), null, icon,
                TrackedWaypoint.Type.VEC3I,
                new Vec3iWaypointData(Vector3i.from(23_000_010, 64, -16_999_990)));
        ClientboundTrackedWaypointPacket blockPacket =
                new ClientboundTrackedWaypointPacket(WaypointOperation.UPDATE, blockWaypoint);
        ClientboundTrackedWaypointPacket shiftedBlock =
                (ClientboundTrackedWaypointPacket) transform.clientbound(blockPacket);
        Vector3i blockPos = ((Vec3iWaypointData) shiftedBlock.getWaypoint().data()).vector();
        assertEquals(23_000_010 - transform.offsetX(), blockPos.getX());
        assertEquals(-16_999_990 - transform.offsetZ(), blockPos.getZ());

        TrackedWaypoint chunkWaypoint = new TrackedWaypoint(UUID.randomUUID(), null, icon,
                TrackedWaypoint.Type.CHUNK,
                new ChunkWaypointData(23_000_000 / 16, -17_000_000 / 16));
        ClientboundTrackedWaypointPacket chunkPacket =
                new ClientboundTrackedWaypointPacket(WaypointOperation.UPDATE, chunkWaypoint);
        ClientboundTrackedWaypointPacket shiftedChunk =
                (ClientboundTrackedWaypointPacket) transform.clientbound(chunkPacket);
        ChunkWaypointData chunk = (ChunkWaypointData) shiftedChunk.getWaypoint().data();
        assertEquals(23_000_000 / 16 - transform.offsetX() / 16, chunk.chunkX());
        assertEquals(-17_000_000 / 16 - transform.offsetZ() / 16, chunk.chunkZ());
    }

    @Test
    void implausibleServerboundMovementIsRejected() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        ServerboundMovePlayerPosPacket tooFar = new ServerboundMovePlayerPosPacket(
                true, false, 1000, 70, 1000);
        assertNull(transform.serverbound(tooFar));
    }

    @Test
    void ordinaryServerboundMovementPassesSafetyGate() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        ClientboundPlayerPositionPacket local =
                (ClientboundPlayerPositionPacket) transform.clientbound(initial);

        ServerboundMovePlayerPosPacket movement = new ServerboundMovePlayerPosPacket(
                true, false, local.getPosition().getX() + 3, 70, local.getPosition().getZ() + 4);
        assertNotNull(transform.serverbound(movement));
    }

    @Test
    void configuredJumpLimitAppliesToPositionAndRotationPackets() {
        ShiftConfig config = new ShiftConfig();
        config.maxJump = 5.0;
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        ClientboundPlayerPositionPacket local =
                (ClientboundPlayerPositionPacket) transform.clientbound(initial);

        ServerboundMovePlayerPosRotPacket tooFar = new ServerboundMovePlayerPosRotPacket(
                true, false, local.getPosition().getX() + 4, 70,
                local.getPosition().getZ() + 4, 90, 0);
        assertNull(transform.serverbound(tooFar));
    }

    @Test
    void chunkCoordinatesUseAlignedChunkOffset() {
        JavaPacketTransform transform = transformer();
        ClientboundPlayerPositionPacket initial = new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0);
        transform.clientbound(initial);

        ClientboundLevelChunkWithLightPacket chunk = new ClientboundLevelChunkWithLightPacket(
                23_000_000 / 16, -17_000_000 / 16, new byte[0], Map.of(),
                new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[]{
                        new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo(
                                1, 64, 2,
                                org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType.CHEST,
                                NbtMap.builder().putInt("x", 23_000_000).putInt("z", -17_000_000).build())
                },
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()));

        ClientboundLevelChunkWithLightPacket shifted =
                (ClientboundLevelChunkWithLightPacket) transform.clientbound(chunk);
        assertEquals(chunk.getX() - transform.offsetX() / 16, shifted.getX());
        assertEquals(chunk.getZ() - transform.offsetZ() / 16, shifted.getZ());
        assertEquals(23_000_000 - transform.offsetX(), shifted.getBlockEntities()[0].getNbt().getInt("x"));
        assertEquals(-17_000_000 - transform.offsetZ(), shifted.getBlockEntities()[0].getNbt().getInt("z"));
    }
}
