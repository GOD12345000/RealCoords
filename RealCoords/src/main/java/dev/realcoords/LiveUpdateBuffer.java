package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * FIFO buffer for live world-state packets that arrive while an immutable replay
 * plan is being dispatched. Overflow is explicit: callers must abort the replay
 * rather than silently lose or reorder world changes.
 */
final class LiveUpdateBuffer {
    record BufferedPacket(Class<? extends MinecraftPacket> packetType, byte[] payload) {
        BufferedPacket {
            Objects.requireNonNull(packetType, "packetType");
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        long memorySize() { return payload.length + 32L; }
    }

    private final long maxBytes;
    private final int maxPackets;
    private final List<BufferedPacket> packets = new ArrayList<>();
    private long storedBytes;

    LiveUpdateBuffer(long maxBytes, int maxPackets) {
        if (maxBytes < 1 || maxPackets < 1) {
            throw new IllegalArgumentException("buffer limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxPackets = maxPackets;
    }

    synchronized boolean offer(MinecraftPacket packet) {
        Objects.requireNonNull(packet, "packet");
        byte[] payload;
        try {
            payload = JavaPacketSnapshot.serialize(packet);
        } catch (RuntimeException exception) {
            return false;
        }
        BufferedPacket snapshot = new BufferedPacket(packet.getClass(), payload);
        if (packets.size() >= maxPackets || storedBytes + snapshot.memorySize() > maxBytes) return false;
        packets.add(snapshot);
        storedBytes += snapshot.memorySize();
        return true;
    }

    synchronized List<BufferedPacket> drain() {
        return packets.isEmpty() ? List.of() : drain(packets.size());
    }

    synchronized List<BufferedPacket> drain(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        int count = Math.min(limit, packets.size());
        List<BufferedPacket> result = List.copyOf(packets.subList(0, count));
        for (BufferedPacket packet : result) storedBytes -= packet.memorySize();
        packets.subList(0, count).clear();
        return result;
    }

    synchronized int size() { return packets.size(); }
    synchronized long storedBytes() { return storedBytes; }

    synchronized void clear() {
        packets.clear();
        storedBytes = 0;
    }
}
