package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded latest-value snapshots for dimension-independent world state. */
final class GlobalStateReplayCache {
    enum Key {
        DIFFICULTY,
        TIME,
        GAME_MODE,
        RAIN_START,
        RAIN_STOP,
        RAIN_LEVEL,
        THUNDER_LEVEL,
        DEFAULT_SPAWN,
        BORDER_INITIALIZE,
        BORDER_CENTER,
        BORDER_LERP_SIZE,
        BORDER_SIZE
    }

    record Snapshot(Key key, Class<? extends MinecraftPacket> packetType, byte[] payload) {
        Snapshot {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(packetType, "packetType");
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private record Stored(Class<? extends MinecraftPacket> type, byte[] payload) {
        long size() { return payload.length + 32L; }
    }

    private final long maxBytes;
    private final LinkedHashMap<Key, Stored> states = new LinkedHashMap<>();
    private long storedBytes;

    GlobalStateReplayCache(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    synchronized boolean put(Key key, Class<? extends MinecraftPacket> type, byte[] payload) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        long cost = payload.length + 32L;
        if (cost > maxBytes) return false;
        Stored previous = states.remove(key);
        if (previous != null) storedBytes -= previous.size();
        Stored replacement = new Stored(type, payload.clone());
        states.put(key, replacement);
        storedBytes += replacement.size();
        while (storedBytes > maxBytes && !states.isEmpty()) {
            Map.Entry<Key, Stored> eldest = states.entrySet().iterator().next();
            storedBytes -= eldest.getValue().size();
            states.remove(eldest.getKey());
        }
        return states.containsKey(key);
    }

    synchronized List<Snapshot> snapshot() {
        List<Snapshot> result = new ArrayList<>(states.size());
        for (Map.Entry<Key, Stored> entry : states.entrySet()) {
            result.add(new Snapshot(entry.getKey(), entry.getValue().type(), entry.getValue().payload()));
        }
        return List.copyOf(result);
    }

    synchronized long storedBytes() { return storedBytes; }
    synchronized int size() { return states.size(); }

    synchronized void clear() {
        states.clear();
        storedBytes = 0;
    }
}
