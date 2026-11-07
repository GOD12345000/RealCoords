package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlobalStateReplayCacheTest {
    @Test
    void retainsLatestValuePerStateKeyAndPreservesIndependentKeys() {
        GlobalStateReplayCache cache = new GlobalStateReplayCache(4096);
        byte[] first = {1, 2};
        byte[] second = {3, 4, 5};
        assertTrue(cache.put(GlobalStateReplayCache.Key.TIME, ClientboundSetTimePacket.class, first));
        assertTrue(cache.put(GlobalStateReplayCache.Key.TIME, ClientboundSetTimePacket.class, second));
        assertTrue(cache.put(GlobalStateReplayCache.Key.RAIN_START, ClientboundGameEventPacket.class,
                new byte[]{6}));

        List<GlobalStateReplayCache.Snapshot> snapshots = cache.snapshot();
        assertEquals(2, snapshots.size());
        assertArrayEquals(second, snapshots.get(0).payload());
        assertEquals(GlobalStateReplayCache.Key.RAIN_START, snapshots.get(1).key());
        second[0] = 99;
        assertArrayEquals(new byte[]{3, 4, 5}, snapshots.get(0).payload());
    }

    @Test
    void enforcesByteBudgetAndClearsAccounting() {
        GlobalStateReplayCache cache = new GlobalStateReplayCache(100);
        assertTrue(cache.put(GlobalStateReplayCache.Key.TIME, ClientboundSetTimePacket.class, new byte[40]));
        assertTrue(cache.put(GlobalStateReplayCache.Key.RAIN_LEVEL, ClientboundGameEventPacket.class, new byte[40]));
        assertTrue(cache.storedBytes() <= 100);
        assertFalse(cache.put(GlobalStateReplayCache.Key.THUNDER_LEVEL,
                ClientboundGameEventPacket.class, new byte[100]));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.storedBytes());
    }
}
