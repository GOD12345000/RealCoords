package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveUpdateBufferTest {
    @Test
    void preservesArrivalOrderAndRoundTripsPacketSnapshots() {
        LiveUpdateBuffer buffer = new LiveUpdateBuffer(4096, 8);
        ClientboundGameEventPacket start = new ClientboundGameEventPacket(GameEvent.START_RAINING, null);
        ClientboundGameEventPacket stop = new ClientboundGameEventPacket(GameEvent.STOP_RAINING, null);
        assertTrue(buffer.offer(start));
        assertTrue(buffer.offer(stop));
        List<LiveUpdateBuffer.BufferedPacket> drained = buffer.drain();
        assertEquals(2, drained.size());
        assertEquals(GameEvent.START_RAINING,
                JavaPacketSnapshot.deserialize(ClientboundGameEventPacket.class,
                        drained.get(0).payload()).getNotification());
        assertEquals(GameEvent.STOP_RAINING,
                JavaPacketSnapshot.deserialize(ClientboundGameEventPacket.class,
                        drained.get(1).payload()).getNotification());
        assertEquals(0, buffer.size());
        assertEquals(0, buffer.storedBytes());
    }

    @Test
    void rejectsOverflowWithoutDroppingPreviouslyBufferedPackets() {
        LiveUpdateBuffer buffer = new LiveUpdateBuffer(100, 1);
        ClientboundGameEventPacket first = new ClientboundGameEventPacket(GameEvent.START_RAINING, null);
        ClientboundGameEventPacket second = new ClientboundGameEventPacket(GameEvent.STOP_RAINING, null);
        assertTrue(buffer.offer(first));
        assertFalse(buffer.offer(second));
        assertEquals(1, buffer.size());
        assertEquals(GameEvent.START_RAINING,
                JavaPacketSnapshot.deserialize(ClientboundGameEventPacket.class,
                        buffer.drain().get(0).payload()).getNotification());
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new LiveUpdateBuffer(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LiveUpdateBuffer(1, 0));
    }
}
