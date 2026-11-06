package dev.realcoords;

import io.netty.channel.embedded.EmbeddedChannel;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RainStrengthValue;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JavaPacketTransformTest {
    private static JavaPacketTransform transformer() {
        ShiftConfig config = new ShiftConfig();
        config.dims.put(0, new ShiftConfig.Dim());
        return new JavaPacketTransform(config);
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
                new org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo[0],
                new org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData(
                        new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()));

        ClientboundLevelChunkWithLightPacket shifted =
                (ClientboundLevelChunkWithLightPacket) transform.clientbound(chunk);
        assertEquals(chunk.getX() - transform.offsetX() / 16, shifted.getX());
        assertEquals(chunk.getZ() - transform.offsetZ() / 16, shifted.getZ());
    }
}
