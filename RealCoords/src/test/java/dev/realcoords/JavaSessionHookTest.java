package dev.realcoords;

import io.netty.channel.embedded.EmbeddedChannel;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
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
        assertTrue(handler.beginSyntheticReset(987655) == false); // overlapping reset is rejected

        ServerboundAcceptTeleportationPacket ordinaryAck =
                new ServerboundAcceptTeleportationPacket(987653, 0, 64, 0, 0, 0);
        assertTrue(channel.writeOutbound(ordinaryAck));
        assertSame(ordinaryAck, channel.readOutbound());

        ServerboundAcceptTeleportationPacket syntheticAck =
                new ServerboundAcceptTeleportationPacket(987654, 0, 64, 0, 0, 0);
        assertFalse(channel.writeOutbound(syntheticAck));
        assertNull(channel.readOutbound());
        assertTrue(handler.beginSyntheticReset(987655));
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertTrue(handler.beginSyntheticReset(987656) == false);
        assertFalse(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        ServerboundAcceptTeleportationPacket secondSyntheticAck =
                new ServerboundAcceptTeleportationPacket(987655, 0, 64, 0, 0, 0);
        assertFalse(channel.writeOutbound(secondSyntheticAck));
        assertNull(channel.readOutbound());

        // Once both synthetic responses have been consumed, normal packets pass.
        assertTrue(channel.writeOutbound(ServerboundPlayerLoadedPacket.INSTANCE));
        assertSame(ServerboundPlayerLoadedPacket.INSTANCE, channel.readOutbound());
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
