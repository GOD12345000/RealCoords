package dev.realcoords;

import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
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
        assertTrue(handler.beginSyntheticReset(987655) == false);
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

        int injected = handler.injectSyntheticResetRespawns(channel.pipeline().context(handler), 777777);
        assertEquals(2, injected);
        ClientboundRespawnPacket temporary = channel.readInbound();
        ClientboundRespawnPacket restored = channel.readInbound();
        assertNotNull(temporary);
        assertNotNull(restored);
        assertEquals("minecraft:overworld_realcoords_reset",
                temporary.getCommonPlayerSpawnInfo().getWorldName().asString());
        assertEquals(world, restored.getCommonPlayerSpawnInfo().getWorldName());

        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertFalse(channel.writeOutbound(new ServerboundAcceptTeleportationPacket(
                777777, 0, 70, 0, 0, 0)));
        assertFalse(handler.beginSyntheticReset(777778));
        channel.finishAndReleaseAll();
    }

    @Test
    void abortRestoresNormalOutboundPacketFlow() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        JavaSessionHook.JavaPacketTransformHandler handler =
                new JavaSessionHook.JavaPacketTransformHandler(new JavaPacketTransform(config), null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(handler.beginSyntheticReset(100));
        handler.abortSyntheticReset();

        ServerboundAcceptTeleportationPacket ack =
                new ServerboundAcceptTeleportationPacket(100, 0, 64, 0, 0, 0);
        assertTrue(channel.writeOutbound(ack));
        assertSame(ack, channel.readOutbound());
        channel.finishAndReleaseAll();
    }
}
