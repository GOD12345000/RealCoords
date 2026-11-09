package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;

import java.util.List;
import java.util.Objects;

/**
 * Immutable replay snapshot with a single forward-only cursor. Live world updates
 * are buffered while the snapshot is replayed and drained last in FIFO order.
 */
final class ReoriginReplayPlan {
    enum Stage { CHUNKS, ENTITIES, GLOBAL_STATE, LIVE_UPDATES, COMPLETE, ABORTED }

    private final List<ChunkReplayCache.ReplayChunk> chunks;
    private final List<EntityReplayCache.ReplayPacket> entities;
    private final List<GlobalStateReplayCache.Snapshot> globalState;
    private final LiveUpdateBuffer liveUpdates;
    private int chunkCursor;
    private int entityCursor;
    private Stage stage = Stage.CHUNKS;

    ReoriginReplayPlan(List<ChunkReplayCache.ReplayChunk> chunks,
                       List<EntityReplayCache.ReplayPacket> entities,
                       List<GlobalStateReplayCache.Snapshot> globalState) {
        this(chunks, entities, globalState, new LiveUpdateBuffer(4L * 1024 * 1024, 4096));
    }

    ReoriginReplayPlan(List<ChunkReplayCache.ReplayChunk> chunks,
                       List<EntityReplayCache.ReplayPacket> entities,
                       List<GlobalStateReplayCache.Snapshot> globalState,
                       LiveUpdateBuffer liveUpdates) {
        this.chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        this.entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        this.globalState = List.copyOf(Objects.requireNonNull(globalState, "globalState"));
        this.liveUpdates = Objects.requireNonNull(liveUpdates, "liveUpdates");
    }

    synchronized boolean bufferLiveUpdate(MinecraftPacket packet) {
        if (stage == Stage.COMPLETE || stage == Stage.ABORTED) return false;
        if (!liveUpdates.offer(packet)) {
            abort();
            return false;
        }
        return true;
    }

    synchronized List<ChunkReplayCache.ReplayChunk> nextChunkBatch(int limit) {
        requireStage(Stage.CHUNKS);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        int end = Math.min(chunks.size(), chunkCursor + limit);
        List<ChunkReplayCache.ReplayChunk> batch = List.copyOf(chunks.subList(chunkCursor, end));
        chunkCursor = end;
        if (chunkCursor == chunks.size()) stage = Stage.ENTITIES;
        return batch;
    }

    synchronized List<EntityReplayCache.ReplayPacket> nextEntityBatch(int limit) {
        requireStage(Stage.ENTITIES);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        int end = Math.min(entities.size(), entityCursor + limit);
        List<EntityReplayCache.ReplayPacket> batch = List.copyOf(entities.subList(entityCursor, end));
        entityCursor = end;
        if (entityCursor == entities.size()) stage = Stage.GLOBAL_STATE;
        return batch;
    }

    synchronized List<GlobalStateReplayCache.Snapshot> finishGlobalState() {
        requireStage(Stage.GLOBAL_STATE);
        stage = Stage.LIVE_UPDATES;
        return globalState;
    }

    synchronized List<LiveUpdateBuffer.BufferedPacket> nextLiveUpdateBatch(int limit) {
        requireStage(Stage.LIVE_UPDATES);
        List<LiveUpdateBuffer.BufferedPacket> batch = liveUpdates.drain(limit);
        if (liveUpdates.size() == 0) stage = Stage.COMPLETE;
        return batch;
    }

    synchronized void abort() {
        liveUpdates.clear();
        stage = Stage.ABORTED;
    }

    synchronized Stage stage() { return stage; }
    synchronized boolean complete() { return stage == Stage.COMPLETE; }
    synchronized boolean aborted() { return stage == Stage.ABORTED; }
    int chunkCount() { return chunks.size(); }
    int entityPacketCount() { return entities.size(); }
    int globalStatePacketCount() { return globalState.size(); }
    synchronized int bufferedLiveUpdateCount() { return liveUpdates.size(); }

    private void requireStage(Stage expected) {
        if (stage != expected) {
            throw new IllegalStateException("Replay stage is " + stage + ", expected " + expected);
        }
    }
}
