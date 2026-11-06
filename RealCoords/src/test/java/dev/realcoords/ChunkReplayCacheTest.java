package dev.realcoords;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkReplayCacheTest {
    @Test
    void roundTripsCompressedAndUncompressedPayloads() {
        byte[] repeated = new byte[8192];
        Arrays.fill(repeated, (byte) 7);
        ChunkReplayCache compressed = new ChunkReplayCache(8, 32_768, true);
        assertTrue(compressed.put(1, 2, repeated));
        assertArrayEquals(repeated, compressed.snapshotNearestFirst(1, 2, 1).getFirst().payload());

        byte[] randomish = "chunk-payload".getBytes(StandardCharsets.UTF_8);
        ChunkReplayCache plain = new ChunkReplayCache(8, 32_768, false);
        assertTrue(plain.put(-3, 9, randomish));
        assertArrayEquals(randomish, plain.snapshotNearestFirst(-3, 9, 1).getFirst().payload());
    }

    @Test
    void payloadsAreDefensivelyCopied() {
        ChunkReplayCache cache = new ChunkReplayCache(8, 32_768, true);
        byte[] input = {1, 2, 3};
        assertTrue(cache.put(0, 0, input));
        input[0] = 99;
        byte[] returned = cache.snapshotNearestFirst(0, 0, 1).getFirst().payload();
        returned[1] = 88;
        assertArrayEquals(new byte[]{1, 2, 3},
                cache.snapshotNearestFirst(0, 0, 1).getFirst().payload());
    }

    @Test
    void evictsLeastRecentlyUsedEntryWhenCountLimitIsReached() {
        ChunkReplayCache cache = new ChunkReplayCache(2, 1024, false);
        cache.put(0, 0, new byte[]{0});
        cache.put(1, 0, new byte[]{1});
        cache.snapshotNearestFirst(0, 0, 1); // touch (0,0)
        cache.put(2, 0, new byte[]{2});

        assertEquals(2, cache.size());
        List<ChunkReplayCache.ReplayChunk> remaining = cache.snapshotNearestFirst(0, 0, 8);
        assertTrue(remaining.stream().anyMatch(c -> c.x() == 0));
        assertFalse(remaining.stream().anyMatch(c -> c.x() == 1));
    }

    @Test
    void refusesOversizedEntryWithoutEvictingExistingData() {
        ChunkReplayCache cache = new ChunkReplayCache(4, 4, false);
        assertTrue(cache.put(0, 0, new byte[]{1, 2}));
        assertFalse(cache.put(1, 0, new byte[]{1, 2, 3, 4, 5}));
        assertEquals(1, cache.size());
        assertEquals(2, cache.storedBytes());
    }

    @Test
    void enforcesByteBudgetAndAccountsReplacement() {
        ChunkReplayCache cache = new ChunkReplayCache(10, 6, false);
        assertTrue(cache.put(0, 0, new byte[]{1, 2, 3}));
        assertTrue(cache.put(1, 0, new byte[]{4, 5, 6}));
        assertEquals(6, cache.storedBytes());
        assertTrue(cache.put(0, 0, new byte[]{7, 8}));
        assertEquals(5, cache.storedBytes());
        assertEquals(2, cache.size());
    }

    @Test
    void evictsOutsideCircularChunkRadius() {
        ChunkReplayCache cache = new ChunkReplayCache(8, 1024, false);
        cache.put(0, 0, new byte[]{0});
        cache.put(3, 4, new byte[]{1});
        cache.put(4, 4, new byte[]{2});
        cache.evictOutside(0, 0, 5);

        assertEquals(2, cache.size());
        assertEquals(2, cache.snapshotNearestFirst(0, 0, 8).size());
    }

    @Test
    void replaysNearestFirstWithStableTieBreakAndLimit() {
        ChunkReplayCache cache = new ChunkReplayCache(8, 1024, false);
        cache.put(2, 0, new byte[]{2});
        cache.put(0, 2, new byte[]{1});
        cache.put(1, 0, new byte[]{0});

        List<ChunkReplayCache.ReplayChunk> result = cache.snapshotNearestFirst(0, 0, 2);
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).x());
        assertEquals(0, result.get(1).x());
        assertEquals(2, result.get(1).z());
    }

    @Test
    void removeAndClearKeepAccountingConsistent() {
        ChunkReplayCache cache = new ChunkReplayCache(8, 1024, false);
        cache.put(1, 2, new byte[]{1, 2, 3});
        assertTrue(cache.remove(1, 2));
        assertFalse(cache.remove(1, 2));
        assertEquals(0, cache.storedBytes());
        cache.put(0, 0, new byte[]{1, 2});
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.storedBytes());
    }
}
