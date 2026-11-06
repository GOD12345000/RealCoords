package dev.realcoords;

import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.junit.jupiter.api.Test;

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

        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(channel.writeOutbound(new ServerboundAcceptTeleportationPacket(
                777777, 0, 70, 0, 0, 0)));
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
}
