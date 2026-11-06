package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded latest-state cache for entities which must be recreated after a
 * Geyser world-cache reset. Relative movement packets are deliberately excluded;
 * callers should only store spawn and absolute-state packets.
 */
final class EntityReplayCache {
    enum Kind {
        SPAWN,
        ATTRIBUTES,
        METADATA,
        EQUIPMENT,
        EFFECT,
        MOTION,
        POSITION,
        LINK,
        PASSENGERS
    }

    record Position(double x, double y, double z, float yaw, float pitch, boolean onGround) { }

    record ReplayPacket(int entityId, Kind kind, Class<? extends MinecraftPacket> packetType,
                        int subKey, byte[] payload) {
        ReplayPacket {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(packetType, "packetType");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record PacketKey(Kind kind, int subKey) { }
    private record StoredPacket(Class<? extends MinecraftPacket> type, byte[] payload) {
        long memorySize() { return payload.length + 32L; }
    }

    private static final class EntityState {
        private final EnumMap<Kind, LinkedHashMap<Integer, StoredPacket>> packets =
                new EnumMap<>(Kind.class);
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private boolean onGround;
        private boolean hasPosition;
        private long memorySize;

        void clear() {
            packets.clear();
            memorySize = 0;
            hasPosition = false;
        }
    }

    private final int maxEntities;
    private final long maxBytes;
    private final LinkedHashMap<Integer, EntityState> entities = new LinkedHashMap<>(16, 0.75f, true);
    private long storedBytes;

    EntityReplayCache(int maxEntities, long maxBytes) {
        if (maxEntities < 1) throw new IllegalArgumentException("maxEntities must be positive");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxEntities = maxEntities;
        this.maxBytes = maxBytes;
    }

    synchronized boolean put(int entityId, Kind kind, int subKey,
                             Class<? extends MinecraftPacket> packetType, byte[] payload) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(packetType, "packetType");
        Objects.requireNonNull(payload, "payload");
        long cost = payload.length + 32L;
        if (cost > maxBytes) return false;

        EntityState state = entities.computeIfAbsent(entityId, ignored -> new EntityState());
        if (kind == Kind.SPAWN) {
            storedBytes -= state.memorySize;
            state.clear();
        }
        LinkedHashMap<Integer, StoredPacket> sameKind =
                state.packets.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
        StoredPacket previous = sameKind.remove(subKey);
        if (previous != null) {
            state.memorySize -= previous.memorySize();
            storedBytes -= previous.memorySize();
        }
        StoredPacket replacement = new StoredPacket(packetType, payload.clone());
        sameKind.put(subKey, replacement);
        state.memorySize += replacement.memorySize();
        storedBytes += replacement.memorySize();
        evictToBudget();
        return entities.containsKey(entityId);
    }

    synchronized void updatePosition(int entityId, double x, double y, double z,
                                     float yaw, float pitch, boolean onGround) {
        EntityState state = entities.get(entityId);
        if (state != null) {
            state.x = x;
            state.y = y;
            state.z = z;
            state.yaw = yaw;
            state.pitch = pitch;
            state.onGround = onGround;
            state.hasPosition = true;
        }
    }

    synchronized void updateDelta(int entityId, double dx, double dy, double dz,
                                  Float yaw, Float pitch, boolean onGround) {
        EntityState state = entities.get(entityId);
        if (state != null && state.hasPosition) {
            state.x += dx;
            state.y += dy;
            state.z += dz;
            if (yaw != null) state.yaw = yaw;
            if (pitch != null) state.pitch = pitch;
            state.onGround = onGround;
        }
    }

    synchronized Position position(int entityId) {
        EntityState state = entities.get(entityId);
        return state == null || !state.hasPosition ? null
                : new Position(state.x, state.y, state.z, state.yaw, state.pitch, state.onGround);
    }

    synchronized void removeEffect(int entityId, int effectId) {
        remove(entityId, Kind.EFFECT, effectId);
    }

    synchronized void removeEntities(int[] entityIds) {
        for (int entityId : entityIds) removeEntity(entityId);
    }

    synchronized boolean removeEntity(int entityId) {
        EntityState removed = entities.remove(entityId);
        if (removed == null) return false;
        storedBytes -= removed.memorySize;
        return true;
    }

    synchronized List<ReplayPacket> snapshotNearestFirst(double centerX, double centerZ,
                                                          double radiusBlocks, int limit) {
        if (limit <= 0 || radiusBlocks < 0) return List.of();
        double radiusSquared = radiusBlocks * radiusBlocks;
        List<Map.Entry<Integer, EntityState>> ordered = new ArrayList<>(entities.entrySet());
        ordered.removeIf(entry -> !entry.getValue().hasPosition
                || distanceSquared(entry.getValue(), centerX, centerZ) > radiusSquared
                || !entry.getValue().packets.containsKey(Kind.SPAWN));
        ordered.sort((a, b) -> {
            int distance = Double.compare(distanceSquared(a.getValue(), centerX, centerZ),
                    distanceSquared(b.getValue(), centerX, centerZ));
            return distance != 0 ? distance : Integer.compare(a.getKey(), b.getKey());
        });

        List<ReplayPacket> result = new ArrayList<>();
        for (Map.Entry<Integer, EntityState> entry : ordered) {
            if (result.size() >= limit) break;
            int entityId = entry.getKey();
            EntityState state = entry.getValue();
            // Spawn every entity before replaying links/passengers or mutable state.
            for (Kind kind : Kind.values()) {
                if (kind != Kind.SPAWN) continue;
                appendPackets(result, entityId, state, kind);
            }
        }
        for (Map.Entry<Integer, EntityState> entry : ordered) {
            if (result.size() >= limit * 16) break;
            for (Kind kind : Kind.values()) {
                if (kind == Kind.SPAWN) continue;
                appendPackets(result, entry.getKey(), entry.getValue(), kind);
                if (result.size() >= limit * 16) break;
            }
        }
        return List.copyOf(result);
    }

    private static void appendPackets(List<ReplayPacket> result, int entityId, EntityState state, Kind kind) {
        LinkedHashMap<Integer, StoredPacket> sameKind = state.packets.get(kind);
        if (sameKind == null) return;
        for (Map.Entry<Integer, StoredPacket> packet : sameKind.entrySet()) {
            result.add(new ReplayPacket(entityId, kind, packet.getValue().type(),
                    packet.getKey(), packet.getValue().payload()));
        }
    }

    synchronized int size() { return entities.size(); }
    synchronized long storedBytes() { return storedBytes; }

    synchronized void clear() {
        entities.clear();
        storedBytes = 0;
    }

    private void remove(int entityId, Kind kind, int subKey) {
        EntityState state = entities.get(entityId);
        if (state == null) return;
        LinkedHashMap<Integer, StoredPacket> sameKind = state.packets.get(kind);
        if (sameKind == null) return;
        StoredPacket removed = sameKind.remove(subKey);
        if (removed != null) {
            state.memorySize -= removed.memorySize();
            storedBytes -= removed.memorySize();
            if (sameKind.isEmpty()) state.packets.remove(kind);
        }
    }

    private void evictToBudget() {
        var iterator = entities.entrySet().iterator();
        while ((entities.size() > maxEntities || storedBytes > maxBytes) && iterator.hasNext()) {
            EntityState removed = iterator.next().getValue();
            storedBytes -= removed.memorySize;
            iterator.remove();
        }
    }

    private static double distanceSquared(EntityState state, double x, double z) {
        double dx = state.x - x;
        double dz = state.z - z;
        return dx * dx + dz * dz;
    }
}
