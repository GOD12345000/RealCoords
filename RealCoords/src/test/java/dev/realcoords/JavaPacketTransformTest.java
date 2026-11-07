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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundTrackedWaypointPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.cloudburstmc.math.vector.Vector3d;
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
    void movementObservedDuringReplayUpdatesBaselineWithoutShiftingAgain() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        config.shiftWhileMoving = true;
        config.cooldownMs = 0;
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(1, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        transform.takeOriginChange();
        int oldOffsetX = transform.offsetX();
        int oldOffsetZ = transform.offsetZ();

        for (int step = 1; step <= 1000; step++) {
            double realX = 23_000_000 + step * 60.0;
            assertTrue(transform.observeMovementDuringReplay(new ServerboundMovePlayerPosPacket(
                    true, false, realX - oldOffsetX, 70, -17_000_000 - oldOffsetZ)));
        }

        assertEquals(23_060_000, transform.realX());
        assertEquals(oldOffsetX, transform.offsetX());
        assertEquals(oldOffsetZ, transform.offsetZ());
        assertNull(transform.takeOriginChange());
    }

    @Test
    void teleportAcknowledgementRestoresRealCoordinates() {
        JavaPacketTransform transform = transformer();
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));

        ServerboundAcceptTeleportationPacket acknowledgement =
                new ServerboundAcceptTeleportationPacket(42, 125.5, 71.25, -84.75, 90, 15);
        ServerboundAcceptTeleportationPacket restored = assertInstanceOf(
                ServerboundAcceptTeleportationPacket.class, transform.serverbound(acknowledgement));

        assertNotEquals(0, transform.offsetX());
        assertNotEquals(0, transform.offsetZ());
        assertEquals(125.5 + transform.offsetX(), restored.getX());
        assertEquals(71.25, restored.getY());
        assertEquals(-84.75 + transform.offsetZ(), restored.getZ());
        assertEquals(42, restored.getId());
        assertEquals(90, restored.getYRot());
        assertEquals(15, restored.getXRot());
    }

    @Test
    void vehicleMovementRestoresOriginBeforeJumpValidationAndForwarding() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        transform.takeOriginChange();

        double localX = -63.5;
        double localZ = -64.25;
        ServerboundMoveVehiclePacket vehicleMove = new ServerboundMoveVehiclePacket(
                Vector3d.from(localX, 70, localZ), 90, 90, true);
        Packet forwarded = transform.serverbound(vehicleMove);

        ServerboundMoveVehiclePacket restored = assertInstanceOf(ServerboundMoveVehiclePacket.class, forwarded);
        assertEquals(localX + transform.offsetX(), restored.getPosition().getX());
        assertEquals(localZ + transform.offsetZ(), restored.getPosition().getZ());
        assertEquals(localX + transform.offsetX(), transform.realX());
        assertEquals(localZ + transform.offsetZ(), transform.realZ());
    }

    @Test
    void vehicleMovementDuringReplayUsesRestoredRealCoordinates() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        transform.takeOriginChange();

        double localX = -62.0;
        double localZ = -63.0;
        assertTrue(transform.observeMovementDuringReplay(new ServerboundMoveVehiclePacket(
                Vector3d.from(localX, 70, localZ), 90, 90, true)));
        assertEquals(localX + transform.offsetX(), transform.realX());
        assertEquals(localZ + transform.offsetZ(), transform.realZ());
    }

    @Test
    void optInLiveMovementShiftDropsTriggeringPacketAndPublishesNewOrigin() {
        // This scenario intentionally walks 60,000 blocks and expects the
        // legacy 50,000-block trigger. Pin every origin threshold explicitly
        // so changes to production defaults do not silently change the test.
        final int testTriggerDistance = 50_000;
        final int testResetDistance = 20_000;
        final int testRecenterOnReload = 2_048;
        final int testAlignment = 256;
        ShiftConfig config = new ShiftConfig();
        ShiftConfig.Dim overworld = new ShiftConfig.Dim();
        overworld.trigger = testTriggerDistance;
        overworld.reset = testResetDistance;
        overworld.alignment = testAlignment;
        config.dims.put(0, overworld);
        config.recenterOnReload = testRecenterOnReload;
        config.shiftWhileMoving = true;
        config.cooldownMs = 0;
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        transform.takeOriginChange(); // consume the initial login origin selection

        int chunkX = 23_000_000 / 16;
        int chunkZ = -17_000_000 / 16;
        transform.clientbound(new ClientboundLevelChunkWithLightPacket(
                chunkX, chunkZ, new byte[]{1}, Map.of(),
                new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[0],
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of())));
        int destinationChunkX = (23_000_000 + 50_100) / 16;
        transform.clientbound(new ClientboundLevelChunkWithLightPacket(
                destinationChunkX, chunkZ, new byte[]{2}, Map.of(),
                new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[0],
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of())));

        int oldOffsetX = transform.offsetX();
        double realX = 23_000_000;
        boolean shifted = false;
        for (int step = 1; step <= 1000 && !shifted; step++) {
            realX += 60;
            ServerboundMovePlayerPosPacket move = new ServerboundMovePlayerPosPacket(
                    true, false, realX - transform.offsetX(), 70, -17_000_000 - transform.offsetZ());
            Packet forwarded = transform.serverbound(move);
            JavaOriginState.Snapshot change = transform.takeOriginChange();
            if (change != null && change.changed()) {
                assertNull(forwarded);
                shifted = true;
                assertNotEquals(oldOffsetX, change.offsetX());
                assertTrue(Math.abs(realX - change.offsetX()) < 256);
                ClientboundPlayerPositionPacket resetPosition = transform.syntheticPlayerPosition(987654);
                assertNotNull(resetPosition);
                assertTrue(Math.abs(resetPosition.getPosition().getX()) < 256);
            } else {
                assertNotNull(forwarded);
            }
        }
        assertTrue(shifted, "continuous movement should eventually request a new origin");
    }

    @Test
    void entityRelativeMovementIsFoldedIntoAbsoluteReplayPosition() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        transform.clientbound(new ClientboundAddEntityPacket(
                77, UUID.randomUUID(), EntityType.ZOMBIE, 23_000_010, 64, -16_999_990,
                10, 20, 30));
        transform.clientbound(new ClientboundMoveEntityPosPacket(
                77, true, 0, null, 5, 2, -4));

        List<Packet> replay = transform.replayCachedEntities(23_000_000, -17_000_000, 256, 8);
        assertEquals(2, replay.size());
        ClientboundEntityPositionSyncPacket position =
                assertInstanceOf(ClientboundEntityPositionSyncPacket.class, replay.get(1));
        assertEquals(23_000_015 - transform.offsetX(), position.getEndPosition().getX());
        assertEquals(66, position.getEndPosition().getY());
        assertEquals(-16_999_994 - transform.offsetZ(), position.getEndPosition().getZ());
    }

    @Test
    void cachedGlobalWeatherEventsReplayInLastUpdateOrder() {
        JavaPacketTransform transform = transformer();
        transform.clientbound(new ClientboundGameEventPacket(
                org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent.START_RAINING, null));
        transform.clientbound(new ClientboundGameEventPacket(
                org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent.STOP_RAINING, null));

        List<Packet> replay = transform.replayGlobalState();
        assertEquals(2, replay.size());
        ClientboundGameEventPacket start = assertInstanceOf(ClientboundGameEventPacket.class, replay.get(0));
        ClientboundGameEventPacket stop = assertInstanceOf(ClientboundGameEventPacket.class, replay.get(1));
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent.START_RAINING,
                start.getNotification());
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent.STOP_RAINING,
                stop.getNotification());
    }

    @Test
    void cachedEntityReplayRestoresSpawnAtShiftedPosition() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        transform.clientbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawnInfo, true, true));
        transform.clientbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));

        ClientboundAddEntityPacket entity = new ClientboundAddEntityPacket(
                99, UUID.randomUUID(), EntityType.ZOMBIE, 23_000_012, 64, -16_999_988,
                0, 0, 0);
        ClientboundSetEntityDataPacket metadata = new ClientboundSetEntityDataPacket(
                99, new EntityMetadata[0]);
        transform.clientbound(entity);
        transform.clientbound(metadata);

        List<Packet> replay = transform.replayCachedEntities(23_000_000, -17_000_000, 256, 8);
        assertEquals(1, replay.size());
        ClientboundAddEntityPacket replayedSpawn =
                assertInstanceOf(ClientboundAddEntityPacket.class, replay.get(0));
        assertEquals(23_000_012 - transform.offsetX(), replayedSpawn.getX());
        assertEquals(-16_999_988 - transform.offsetZ(), replayedSpawn.getZ());
        assertEquals(1, transform.cachedEntityCount());
        assertEquals(1, transform.replayCachedEntities(23_000_000, -17_000_000, 256, 8).size());
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
    @Test
    void transitionGraceAcceptsOneHundredBlocksButRejectsOffsetScaleJump() {
        ShiftConfig strictConfig = new ShiftConfig();
        strictConfig.dims.put(0, new ShiftConfig.Dim());
        JavaOriginState strictOrigin = new JavaOriginState(strictConfig);
        strictOrigin.absolutePosition(0, 0, 10);
        JavaPacketTransform strict = new JavaPacketTransform(strictOrigin, 64.0, strictConfig);
        ServerboundMovePlayerPosPacket strictJump =
                new ServerboundMovePlayerPosPacket(true, false, 100, 70, 0);
        assertNull(strict.serverbound(strictJump, false, false),
                "outside transition grace, movement beyond max-serverbound-jump must remain rejected");

        ShiftConfig graceConfig = new ShiftConfig();
        graceConfig.dims.put(0, new ShiftConfig.Dim());
        JavaOriginState ordinaryOrigin = new JavaOriginState(graceConfig);
        ordinaryOrigin.absolutePosition(0, 0, 10);
        JavaPacketTransform ordinary = new JavaPacketTransform(ordinaryOrigin, 64.0, graceConfig);
        ServerboundMovePlayerPosPacket oneHundredBlocks =
                new ServerboundMovePlayerPosPacket(true, false, 100, 70, 0);
        assertNotNull(ordinary.serverbound(oneHundredBlocks, false, true),
                "a 100-block movement must fit within the bounded transition grace limit");

        JavaOriginState staleOrigin = new JavaOriginState(graceConfig);
        staleOrigin.absolutePosition(0, 0, 10);
        JavaPacketTransform stale = new JavaPacketTransform(staleOrigin, 64.0, graceConfig);
        ServerboundMovePlayerPosPacket offsetScaleJump =
                new ServerboundMovePlayerPosPacket(true, false, 50_000, 70, 0);
        assertNull(stale.serverbound(offsetScaleJump, false, true),
                "a 50,000-block stale-coordinate jump must be rejected during transition grace");
    }
    @Test
    void rejectedTransitionJumpReportsMeasuredDistanceAndFiniteLimit() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        config.transitionGraceMaxJump = 256.0;
        JavaOriginState origin = new JavaOriginState(config);
        origin.absolutePosition(0, 0, 10);
        JavaPacketTransform transform = new JavaPacketTransform(origin, 64.0, config);

        ServerboundMovePlayerPosPacket stale = new ServerboundMovePlayerPosPacket(
                true, false, 50_000, 70, 0);
        assertNull(transform.serverbound(stale, false, 256.0));
        JavaPacketTransform.MovementRejection rejection = transform.takeMovementRejection();
        assertNotNull(rejection);
        assertEquals(50_000.0, rejection.distance(), 0.001);
        assertEquals(256.0, rejection.limit());
    }

    @Test
    void programmaticConfigCannotMakeTransitionLimitSmallerThanNormalLimit() {
        ShiftConfig config = new ShiftConfig();
        config.maxJump = 512.0;
        JavaPacketTransform transform = new JavaPacketTransform(config);
        assertEquals(512.0, transform.transitionGraceMaxJump());
    }

}
