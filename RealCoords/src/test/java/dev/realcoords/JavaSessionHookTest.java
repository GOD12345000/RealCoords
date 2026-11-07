package dev.realcoords;

import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;


import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JavaSessionHookTest {
    @Test
    void handlerSuppressesOnlySyntheticResetResponses() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(987654));
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertNull(channel.readOutbound());
        assertFalse(handler.beginSyntheticReset(987655)); // overlapping reset is rejected

        ServerboundAcceptTeleportationPacket ordinaryAck =
                new ServerboundAcceptTeleportationPacket(987653, 0, 64, 0, 0, 0);
        assertTrue(channel.writeOutbound(ordinaryAck));
        assertSame(ordinaryAck, channel.readOutbound());

        ServerboundAcceptTeleportationPacket syntheticAck =
                new ServerboundAcceptTeleportationPacket(987654, 0, 64, 0, 0, 0);
        assertFalse(channel.writeOutbound(syntheticAck));
        assertNull(channel.readOutbound());
        // The second respawn acknowledgement is also synthetic; the gate stays active.
        assertFalse(handler.beginSyntheticReset(987655));
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertNull(channel.readOutbound());

        // Once both synthetic responses have been consumed, normal packets pass.
        assertTrue(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertSame(ServerboundPlayerLoadedPacket.INSTANCE, channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void injectsBothSyntheticRespawnsIntoGeyserInboundPipeline() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }

        int injected = handler.injectSyntheticReset(channel.pipeline().context(handler), 777777);
        assertEquals(3, injected);
        ClientboundRespawnPacket temporary = channel.readInbound();
        ClientboundRespawnPacket restored = channel.readInbound();
        ClientboundPlayerPositionPacket syntheticPosition = channel.readInbound();
        assertNotNull(temporary);
        assertNotNull(restored);
        assertNotNull(syntheticPosition);
        assertEquals(777777, syntheticPosition.getId());
        assertTrue(syntheticPosition.getRelatives().isEmpty());
        assertEquals(23_000_000 - transform.offsetX(), syntheticPosition.getPosition().getX());
        assertEquals(-17_000_000 - transform.offsetZ(), syntheticPosition.getPosition().getZ());
        assertEquals("minecraft:overworld_realcoords_reset",
                temporary.getCommonPlayerSpawnInfo().getWorldName().asString());
        assertEquals(world, restored.getCommonPlayerSpawnInfo().getWorldName());

        assertFalse(channel.writeOutbound(new ServerboundMovePlayerPosPacket(
                true, false, 10, 70, 10))); // movement is frozen during reset
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(channel.writeOutbound(new ServerboundAcceptTeleportationPacket(
                777777, 0, 70, 0, 0, 0)));
        assertFalse(channel.writeOutbound(new ServerboundMovePlayerPosPacket(
                true, false, 10, 70, 10))); // movement remains frozen while replay is active
        assertFalse(handler.beginSyntheticReset(777778)); // a replay transaction is still active
        channel.close();
        channel.finishAndReleaseAll();
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void buffersLiveWorldPacketsUntilAfterAllReplayStages() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ReoriginReplayPlan plan = handler.prepareReplayPlan(0, 0, 0, 0, 256);
        ClientboundGameEventPacket live = new ClientboundGameEventPacket(GameEvent.START_RAINING, null);

        channel.writeInbound(live);
        assertNull(channel.readInbound());

        handler.replayChunkBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayEntityBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayGlobalState(channel.pipeline().context(handler), plan);
        assertEquals(ReoriginReplayPlan.Stage.LIVE_UPDATES, plan.stage());
        handler.replayLiveUpdates(channel.pipeline().context(handler), plan, 8);

        ClientboundGameEventPacket replayed = channel.readInbound();
        assertNotNull(replayed);
        assertEquals(GameEvent.START_RAINING, replayed.getNotification());
        assertTrue(plan.complete());
        assertEquals(ReoriginTransition.State.NORMAL, handler.transitionState());
        channel.finishAndReleaseAll();
    }

    @Test
    void abortClosesSessionInsteadOfAllowingLateSyntheticAcknowledgementsToLeak() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(100));
        handler.abortSyntheticReset();

        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void buffersWorldUpdatesFromPrepareUntilReplayPlanIsCreated() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(333));
        ClientboundGameEventPacket live = new ClientboundGameEventPacket(GameEvent.START_RAINING, null);

        channel.writeInbound(live);
        assertNull(channel.readInbound(),
                "clientbound world updates must be held from PREPARE, before a replay plan exists");

        ReoriginReplayPlan plan = handler.prepareReplayPlan(0, 0, 0, 0, 256);
        assertEquals(1, plan.globalStatePacketCount(),
                "the live update must update the global-state cache when it arrives");
        assertEquals(1, plan.bufferedLiveUpdateCount(),
                "the raw live update must be transferred into the replay plan");

        handler.replayChunkBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayEntityBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayGlobalState(channel.pipeline().context(handler), plan);
        handler.replayLiveUpdates(channel.pipeline().context(handler), plan, 8);

        ClientboundGameEventPacket cached = channel.readInbound();
        ClientboundGameEventPacket liveAfterReplay = channel.readInbound();
        assertNotNull(cached);
        assertNotNull(liveAfterReplay);
        assertEquals(GameEvent.START_RAINING, cached.getNotification());
        assertEquals(GameEvent.START_RAINING, liveAfterReplay.getNotification());
        assertTrue(plan.complete());
        channel.finishAndReleaseAll();
    }

    @Test
    void passesControlAndChunkBatchPacketsImmediatelyDuringPrepare() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        assertTrue(handler.beginSyntheticReset(444));

        ClientboundPingPacket ping = new ClientboundPingPacket(12);
        ClientboundKeepAlivePacket keepAlive = new ClientboundKeepAlivePacket(34L);
        ClientboundChunkBatchStartPacket batchStart = new ClientboundChunkBatchStartPacket();
        ClientboundChunkBatchFinishedPacket batchFinished = new ClientboundChunkBatchFinishedPacket(2);

        assertTrue(channel.writeInbound(ping));
        assertTrue(channel.writeInbound(keepAlive));
        assertTrue(channel.writeInbound(batchStart));
        assertTrue(channel.writeInbound(batchFinished));
        assertSame(ping, channel.readInbound());
        assertSame(keepAlive, channel.readInbound());
        assertSame(batchStart, channel.readInbound());
        assertSame(batchFinished, channel.readInbound());

        ServerboundPongPacket pong = new ServerboundPongPacket(12);
        ServerboundKeepAlivePacket response = new ServerboundKeepAlivePacket(34L);
        ServerboundChunkBatchReceivedPacket batchReceived = new ServerboundChunkBatchReceivedPacket(1.0f);
        assertTrue(channel.writeOutbound(pong));
        assertSame(pong, channel.readOutbound());
        assertTrue(channel.writeOutbound(response));
        assertSame(response, channel.readOutbound());
        assertTrue(channel.writeOutbound(batchReceived));
        assertSame(batchReceived, channel.readOutbound());

        channel.close();
        channel.finishAndReleaseAll();
    }

    @Test
    void replayDoesNotEmitChunkBatchMarkers() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ReoriginReplayPlan plan = handler.prepareReplayPlan(0, 0, 0, 0, 256);

        handler.replayChunkBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayEntityBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayGlobalState(channel.pipeline().context(handler), plan);
        handler.replayLiveUpdates(channel.pipeline().context(handler), plan, 8);

        Object packet;
        while ((packet = channel.readInbound()) != null) {
            assertFalse(packet instanceof ClientboundChunkBatchStartPacket);
            assertFalse(packet instanceof ClientboundChunkBatchFinishedPacket);
        }
        assertTrue(plan.complete());
        channel.finishAndReleaseAll();
    }

    @Test
    void queuesRealPlayerPositionDuringResetAndReleasesItWithItsOwnTeleportId() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        config.dims.get(0).trigger = 1_000;
        config.dims.get(0).reset = 500;
        JavaPacketTransform transform = new JavaPacketTransform(config);
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }

        assertTrue(handler.beginSyntheticReset(-777));
        ClientboundPlayerPositionPacket realPosition = new ClientboundPlayerPositionPacket(
                4242, 24_000_000, 71, -17_000_100, 0, 0, 0, 0, 0);
        channel.writeInbound(realPosition);
        assertTrue(channel.isOpen(),
                "real backend player position must be queued during reset, not fail the session closed");
        assertNull(channel.readInbound(),
                "real backend player position must be queued during reset, not treated as another reset");

        ServerboundAcceptTeleportationPacket realAck =
                new ServerboundAcceptTeleportationPacket(4242, 0, 71, 0, 0, 0);
        assertTrue(channel.writeOutbound(realAck));
        ServerboundAcceptTeleportationPacket forwardedAck = channel.readOutbound();
        assertSame(realAck, forwardedAck,
                "Geyser's acknowledgement for the real teleport must pass through unchanged");
        assertEquals(4242, forwardedAck.getId());

        ReoriginReplayPlan plan = handler.prepareReplayPlan(0, 0, 0, 0, 256);
        assertEquals(1, plan.bufferedLiveUpdateCount());
        handler.replayChunkBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayEntityBatch(channel.pipeline().context(handler), plan, 8);
        handler.replayGlobalState(channel.pipeline().context(handler), plan);
        handler.replayLiveUpdates(channel.pipeline().context(handler), plan, 8);

        ClientboundPlayerPositionPacket released = channel.readInbound();
        assertNotNull(released, "real player position must be released after replay");
        assertEquals(4242, released.getId(), "real teleport id must not be replaced by synthetic id");
        assertNotEquals(24_000_000, released.getPosition().getX(),
                "queued real position must be transformed with the current origin at release");
        assertTrue(plan.complete());
        channel.close();
        channel.finishAndReleaseAll();
    }

    @Test
    void realLoginDuringResetRemainsFailClosed() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        assertTrue(handler.beginSyntheticReset(-778));
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);

        channel.writeInbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));

        assertFalse(channel.isOpen(), "real ClientboundLoginPacket during reset must fail closed");
        channel.finishAndReleaseAll();
    }

    @Test
    void realRespawnDuringResetRemainsFailClosed() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        assertTrue(handler.beginSyntheticReset(-779));
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);

        channel.writeInbound(new ClientboundRespawnPacket(spawn, true, true));

        assertFalse(channel.isOpen(), "real ClientboundRespawnPacket during reset must fail closed");
        channel.finishAndReleaseAll();
    }

    @Test
    void syntheticPlayerPositionBypassesTheRealPositionQueue() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }

        int injected = handler.injectSyntheticReset(channel.pipeline().context(handler), -780);
        assertEquals(3, injected);
        assertNotNull(channel.readInbound());
        assertNotNull(channel.readInbound());
        ClientboundPlayerPositionPacket syntheticPosition = channel.readInbound();
        assertNotNull(syntheticPosition);
        assertEquals(-780, syntheticPosition.getId());

        ReoriginReplayPlan plan = handler.prepareReplayPlan(0, 0, 0, 0, 256);
        assertEquals(0, plan.bufferedLiveUpdateCount(),
                "synthetic reset position must never enter the real backend position queue");
        channel.close();
        channel.finishAndReleaseAll();
    }


    @Test
    void realContainerPacketsDriveHandlerDeferralState() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        handler.deferralState().initialize(42);

        ClientboundOpenScreenPacket open = new ClientboundOpenScreenPacket(
                3, ContainerType.GENERIC_9X3, Component.text("Chest"));
        ClientboundContainerClosePacket close = new ClientboundContainerClosePacket(3);
        channel.writeInbound(open);
        assertTrue(handler.deferralState().isContainerOpen());
        assertTrue(handler.deferralState().shouldDefer());
        channel.writeInbound(close);
        assertFalse(handler.deferralState().isContainerOpen());
        assertFalse(handler.deferralState().shouldDefer());
        channel.finishAndReleaseAll();
    }

    @Test
    void realPassengerPacketsOnlyTrackTheLocalPlayer() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        handler.deferralState().initialize(42);

        channel.writeInbound(new ClientboundSetPassengersPacket(100, new int[]{7, 8}));
        assertFalse(handler.deferralState().isRiding());
        channel.writeInbound(new ClientboundSetPassengersPacket(101, new int[]{42, 8}));
        assertTrue(handler.deferralState().isRiding());
        assertTrue(handler.deferralState().shouldDefer());
        channel.writeInbound(new ClientboundSetPassengersPacket(100, new int[]{7}));
        assertTrue(handler.deferralState().isRiding());
        channel.writeInbound(new ClientboundSetPassengersPacket(101, new int[]{8}));
        assertFalse(handler.deferralState().isRiding());
        channel.finishAndReleaseAll();
    }

    @Test
    void realPlayerMetadataPacketsTrackPoseAndGlidingOnlyForSelfEntity() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        handler.deferralState().initialize(42);

        EntityMetadata<?, ?>[] otherMetadata = new EntityMetadata[]{
                new ObjectEntityMetadata<>(6, MetadataTypes.POSE, Pose.SLEEPING),
                new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) 0x80)
        };
        channel.writeInbound(new ClientboundSetEntityDataPacket(99, otherMetadata));
        assertFalse(handler.deferralState().shouldDefer());

        channel.writeInbound(new ClientboundSetEntityDataPacket(42, new EntityMetadata[]{
                new ObjectEntityMetadata<>(6, MetadataTypes.POSE, Pose.SLEEPING)}));
        assertTrue(handler.deferralState().isSleeping());
        assertTrue(handler.deferralState().shouldDefer());
        channel.writeInbound(new ClientboundSetEntityDataPacket(42, new EntityMetadata[]{
                new ObjectEntityMetadata<>(6, MetadataTypes.POSE, Pose.STANDING)}));
        assertFalse(handler.deferralState().isSleeping());
        channel.writeInbound(new ClientboundSetEntityDataPacket(42, new EntityMetadata[]{
                new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) 0x80)}));
        assertTrue(handler.deferralState().isGliding());
        channel.writeInbound(new ClientboundSetEntityDataPacket(42, new EntityMetadata[]{
                new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) 0)}));
        assertFalse(handler.deferralState().isGliding());
        channel.finishAndReleaseAll();
    }

    @Test
    void realLoginAndRespawnPacketsInitializeAndResetHandlerState() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);

        channel.writeInbound(new ClientboundLoginPacket(42, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        assertEquals(42, handler.deferralState().selfEntityId());
        assertFalse(handler.deferralState().shouldDefer());

        channel.writeInbound(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3,
                Component.text("Chest")));
        assertTrue(handler.deferralState().shouldDefer());
        channel.writeInbound(new ClientboundRespawnPacket(spawn, true, false));
        assertFalse(handler.deferralState().shouldDefer());
        assertEquals(42, handler.deferralState().selfEntityId());
        channel.finishAndReleaseAll();
    }

    @Test
    void respawnDuringOpenContainerClearsContainerDeferral() {
        assertRespawnResetsState(channel -> channel.writeInbound(new ClientboundOpenScreenPacket(
                3, ContainerType.GENERIC_9X3, Component.text("Chest"))));
    }

    @Test
    void respawnWhileRidingClearsPassengerDeferral() {
        assertRespawnResetsState(channel -> channel.writeInbound(
                new ClientboundSetPassengersPacket(100, new int[]{42})));
    }

    @Test
    void dimensionChangeWhileSleepingClearsPoseDeferral() {
        assertRespawnResetsState(channel -> channel.writeInbound(new ClientboundSetEntityDataPacket(42,
                new EntityMetadata[]{new ObjectEntityMetadata<>(6, MetadataTypes.POSE, Pose.SLEEPING)})));
    }

    private static void assertRespawnResetsState(java.util.function.Consumer<EmbeddedChannel> setup) {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(42, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        setup.accept(channel);
        assertTrue(handler.deferralState().shouldDefer());
        channel.writeInbound(new ClientboundRespawnPacket(spawn, true, false));
        assertFalse(handler.deferralState().shouldDefer());
        channel.finishAndReleaseAll();
    }


    @Test
    void transitionTimeoutFailsClosedAndRecordsResetStage() throws Exception {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        config.transitionTimeoutTicks = 1;
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(-801));
        Thread.sleep(120);
        channel.runScheduledPendingTasks();

        assertEquals(ReoriginTransition.State.FAILED, handler.transitionState());
        assertEquals(ReoriginTransition.State.RESET, handler.transitionFailure().failedAt());
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void disconnectDuringTransitionRecordsFailureAndCleansUp() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(-802));
        channel.close();
        channel.runPendingTasks();

        assertEquals(ReoriginTransition.State.FAILED, handler.transitionState());
        assertEquals(ReoriginTransition.State.RESET, handler.transitionFailure().failedAt());
        assertTrue(handler.transitionFailure().reason().contains("disconnect"));
        channel.finishAndReleaseAll();
    }

    @Test
    void exceptionDuringTransitionRecordsFailureAndClosesChannel() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(-803));
        channel.pipeline().fireExceptionCaught(new IllegalStateException("test transition failure"));

        assertEquals(ReoriginTransition.State.FAILED, handler.transitionState());
        assertEquals(ReoriginTransition.State.RESET, handler.transitionFailure().failedAt());
        assertTrue(handler.transitionFailure().reason().contains("exception"));
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void livePlayerPositionQueueOverflowFailsTransitionAndClosesChannel() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        assertTrue(handler.beginSyntheticReset(-804));

        for (int i = 0; i < 4097 && channel.isOpen(); i++) {
            channel.writeInbound(new ClientboundPlayerPositionPacket(
                    i, 100 + i, 70, 200 + i, 0, 0, 0, 0, 0));
        }

        assertEquals(ReoriginTransition.State.FAILED, handler.transitionState());
        assertEquals(ReoriginTransition.State.RESET, handler.transitionFailure().failedAt());
        assertTrue(handler.transitionFailure().reason().contains("queue overflow"));
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }
    @Test
    void handlerComparisonAtSixtyThousandBlocksUsesFiftyThousandVersusOneHundredThousandTrigger() {
        assertTrue(handlerShiftsAtDistance(50_000, 60_000),
                "the 50,000-block trigger must re-origin on the shared 60,000-block walk fixture");
        assertFalse(handlerShiftsAtDistance(100_000, 60_000),
                "the 100,000-block trigger must not re-origin on the same 60,000-block walk fixture");
    }

    @Test
    void handlerComparisonChangesOnlyTriggerDistanceOutcome() {
        assertTrue(handlerShiftsAtDistance(50_000),
                "the handler with a 50,000-block trigger must re-origin at the shared 50,100-block fixture point");
        assertFalse(handlerShiftsAtDistance(50_100),
                "the otherwise identical handler with a 50,100-block trigger must not re-origin at that point");
    }

    private static boolean handlerShiftsAtDistance(int triggerDistance) {
        return handlerShiftsAtDistance(triggerDistance, 50_100);
    }

    private static boolean handlerShiftsAtDistance(int triggerDistance, int movementDistance) {
        ShiftConfig config = new ShiftConfig();
        ShiftConfig.Dim overworld = new ShiftConfig.Dim();
        overworld.trigger = triggerDistance;
        overworld.reset = 20_000;
        overworld.alignment = 256;
        config.dims.put(0, overworld);
        config.recenterOnReload = 2_048;
        config.shiftWhileMoving = true;
        config.cooldownMs = 0;
        JavaPacketTransform transform = new JavaPacketTransform(config);
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(1, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }

        int destinationChunkX = (23_000_000 + movementDistance) / 16;
        int destinationChunkZ = -17_000_000 / 16;
        channel.writeInbound(new ClientboundLevelChunkWithLightPacket(
                destinationChunkX, destinationChunkZ, new byte[]{1}, Map.of(),
                new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[0],
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of())));
        while (channel.readInbound() != null) { }

        boolean shifted = false;
        for (int step = 1; step <= Math.ceil(movementDistance / 60.0); step++) {
            double realX = 23_000_000 + Math.min(step * 60.0, movementDistance);
            ServerboundMovePlayerPosPacket movement = new ServerboundMovePlayerPosPacket(
                    true, false, realX - transform.offsetX(), 70, -17_000_000 - transform.offsetZ());
            channel.writeOutbound(movement);
            if (handler.transitionState() == ReoriginTransition.State.RESET) {
                shifted = true;
                break;
            }
            channel.readOutbound();
        }

        channel.finishAndReleaseAll();
        return shifted;
    }
    @Test
    void walkFallbackKickDisconnectsWhenDeferredLocalCoordinateExceedsEightTimesTrigger() {
        ShiftConfig config = new ShiftConfig();
        ShiftConfig.Dim dim = new ShiftConfig.Dim();
        dim.trigger = 50_000;
        config.dims.put(0, dim);
        config.walkFallback = "kick";
        config.maxJump = 1_000_000;
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(42, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(1, 0, 70, 0, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }
        channel.writeInbound(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3, Component.text("Chest")));

        channel.writeOutbound(new ServerboundMovePlayerPosPacket(true, false, 400_001, 70, 0));

        assertFalse(channel.isOpen(), "kick fallback must disconnect after local coordinate exceeds 8x trigger");
        channel.finishAndReleaseAll();
    }

    @Test
    void walkFallbackNoneKeepsDeferringAndDoesNotStartAReorigin() {
        ShiftConfig config = new ShiftConfig();
        ShiftConfig.Dim dim = new ShiftConfig.Dim();
        dim.trigger = 50_000;
        config.dims.put(0, dim);
        config.walkFallback = "none";
        config.maxJump = 1_000_000;
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(42, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(1, 0, 70, 0, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }
        channel.writeInbound(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3, Component.text("Chest")));

        ServerboundMovePlayerPosPacket movement = new ServerboundMovePlayerPosPacket(true, false, 400_001, 70, 0);
        channel.writeOutbound(movement);

        assertTrue(channel.isOpen());
        ServerboundMovePlayerPosPacket forwarded = channel.readOutbound();
        assertNotNull(forwarded, "none fallback must continue forwarding movement");
        assertEquals(400_001, forwarded.getX(), "none fallback must not rewrite the player's local coordinate");
        assertTrue(handler.deferralState().shouldDefer());
        assertEquals(ReoriginTransition.State.NORMAL, handler.transitionState());
        channel.finishAndReleaseAll();
    }

    @Test
    void walkFallbackReoriginStartsResetWhenNearbyChunkCacheExists() {
        ShiftConfig config = new ShiftConfig();
        ShiftConfig.Dim dim = new ShiftConfig.Dim();
        dim.trigger = 50_000;
        config.dims.put(0, dim);
        config.walkFallback = "reorigin";
        config.maxJump = 1_000_000;
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(42, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(1, 0, 70, 0, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }
        int chunkX = 400_001 / 16;
        channel.writeInbound(new ClientboundLevelChunkWithLightPacket(chunkX, 0, new byte[]{1}, Map.of(),
                new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[0],
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of())));
        while (channel.readInbound() != null) { }
        channel.writeInbound(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3, Component.text("Chest")));

        channel.writeOutbound(new ServerboundMovePlayerPosPacket(true, false, 400_001, 70, 0));

        assertEquals(ReoriginTransition.State.RESET, handler.transitionState());
        assertTrue(handler.deferralState().shouldDefer(), "the deferral condition remains true until the transition resets the world");
        channel.close();
        channel.finishAndReleaseAll();
    }
    @Test
    void boundedJumpGuardUsesTheSameAcceptedPositionAcrossTransitionPhases() throws Exception {
        for (ReoriginTransition.State phase : List.of(
                ReoriginTransition.State.PREPARE,
                ReoriginTransition.State.RESET,
                ReoriginTransition.State.REPLAY,
                ReoriginTransition.State.DRAIN)) {
            ShiftConfig config = new ShiftConfig();
            config.dims.put(0, new ShiftConfig.Dim());
            JavaPacketTransform transform = new JavaPacketTransform(config);
            JavaSessionHook.JavaPacketTransformHandler handler =
                    new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
            EmbeddedChannel channel = new EmbeddedChannel(handler);
            initializeMovementFixture(channel, transform);

            if (phase == ReoriginTransition.State.PREPARE || phase == ReoriginTransition.State.RESET) {
                assertTrue(handler.beginSyntheticReset(-900 - phase.ordinal()));
            } else {
                handler.prepareReplayPlan(0, 0, transform.realX(), transform.realZ(), 256);
            }
            setTransitionPhase(handler, phase);
            assertEquals(phase, handler.transitionState(), "the packet must be tested in the requested phase");

            double before = transform.realX();
            double localX = before - transform.offsetX();
            ServerboundMovePlayerPosPacket oneHundred =
                    new ServerboundMovePlayerPosPacket(true, false, localX + 100, 70,
                            transform.realZ() - transform.offsetZ());
            assertFalse(channel.writeOutbound(oneHundred),
                    "movement remains frozen/consumed during " + phase);
            assertNull(channel.readOutbound());
            assertEquals(before + 100, transform.realX(), 0.001,
                    "100 blocks must be accepted by the same bounded guard during " + phase);

            double accepted = transform.realX();
            ServerboundMovePlayerPosPacket offsetScale =
                    new ServerboundMovePlayerPosPacket(true, false,
                            accepted - transform.offsetX() + 50_000, 70,
                            transform.realZ() - transform.offsetZ());
            assertFalse(channel.writeOutbound(offsetScale),
                    "50,000-block stale-coordinate movement must not be forwarded during " + phase);
            assertNull(channel.readOutbound());
            assertEquals(accepted, transform.realX(), 0.001,
                    "rejected movement must not replace the last accepted real position during " + phase);
            channel.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void boundedJumpGuardAppliesInGraceAndReturnsToStrictLimitAfterGrace() throws Exception {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaPacketTransform transform = new JavaPacketTransform(config);
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(transform, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        initializeMovementFixture(channel, transform);
        transitionCompletedNow(handler);

        double before = transform.realX();
        ServerboundMovePlayerPosPacket oneHundred = new ServerboundMovePlayerPosPacket(
                true, false, before - transform.offsetX() + 100, 70,
                transform.realZ() - transform.offsetZ());
        assertTrue(channel.writeOutbound(oneHundred));
        ServerboundMovePlayerPosPacket forwarded = channel.readOutbound();
        assertNotNull(forwarded);
        assertEquals(before + 100, forwarded.getX(), 0.001);

        double accepted = transform.realX();
        ServerboundMovePlayerPosPacket offsetScale = new ServerboundMovePlayerPosPacket(
                true, false, accepted - transform.offsetX() + 50_000, 70,
                transform.realZ() - transform.offsetZ());
        assertFalse(channel.writeOutbound(offsetScale),
                "50,000-block movement must be rejected during grace, not forwarded");
        assertNull(channel.readOutbound());
        assertEquals(accepted, transform.realX(), 0.001);

        setTransitionCompletedAt(handler, System.nanoTime() - 2_000_000_000L);
        ServerboundMovePlayerPosPacket afterGrace = new ServerboundMovePlayerPosPacket(
                true, false, accepted - transform.offsetX() + 100, 70,
                transform.realZ() - transform.offsetZ());
        assertFalse(channel.writeOutbound(afterGrace),
                "after grace expires, max-serverbound-jump must return to 64 blocks");
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    private static void initializeMovementFixture(EmbeddedChannel channel, JavaPacketTransform transform) {
        Key world = Key.key("minecraft:overworld");
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(0, world, 0L, GameMode.SURVIVAL,
                null, false, false, null, 0, 63);
        channel.writeInbound(new ClientboundLoginPacket(42, false, new Key[]{world},
                20, 10, 10, false, true, false, spawn, true, true));
        channel.writeInbound(new ClientboundPlayerPositionPacket(
                1, 23_000_000, 70, -17_000_000, 0, 0, 0, 0, 0));
        while (channel.readInbound() != null) { }
    }

    private static void setTransitionPhase(JavaSessionHook.JavaPacketTransformHandler handler,
                                           ReoriginTransition.State phase) throws Exception {
        java.lang.reflect.Field transitionField = handler.getClass().getDeclaredField("transition");
        transitionField.setAccessible(true);
        ReoriginTransition transition = (ReoriginTransition) transitionField.get(handler);
        java.lang.reflect.Field stateField = ReoriginTransition.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(transition, phase);
    }

    private static void transitionCompletedNow(JavaSessionHook.JavaPacketTransformHandler handler) throws Exception {
        java.lang.reflect.Field timingField = handler.getClass().getDeclaredField("timingPolicy");
        timingField.setAccessible(true);
        TransitionTimingPolicy policy = (TransitionTimingPolicy) timingField.get(handler);
        policy.transitionCompleted(System.nanoTime());
    }

    private static void setTransitionCompletedAt(JavaSessionHook.JavaPacketTransformHandler handler,
                                                  long completedAtNanos) throws Exception {
        java.lang.reflect.Field timingField = handler.getClass().getDeclaredField("timingPolicy");
        timingField.setAccessible(true);
        TransitionTimingPolicy policy = (TransitionTimingPolicy) timingField.get(handler);
        java.lang.reflect.Field completedField =
                TransitionTimingPolicy.class.getDeclaredField("lastTransitionCompletedNanos");
        completedField.setAccessible(true);
        completedField.setLong(policy, completedAtNanos);
    }

}
