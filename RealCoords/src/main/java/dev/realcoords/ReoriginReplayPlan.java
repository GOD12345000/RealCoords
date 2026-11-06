package dev.realcoords;

import java.util.List;
import java.util.Objects;

/**
 * Immutable replay snapshot with a single forward-only cursor. The snapshot is
 * taken after the synthetic reset acknowledgements so live cache updates cannot
 * reorder or duplicate packets while a bounded replay is in progress.
 */
final class ReoriginReplayPlan {
    enum Stage { CHUNKS, ENTITIES, GLOBAL_STATE, COMPLETE }

    private final List<ChunkReplayCache.ReplayChunk> chunks;
    private final List<EntityReplayCache.ReplayPacket> entities;
    private final List<GlobalStateReplayCache.Snapshot> globalState;
    private int chunkCursor;
    private int entityCursor;
    private Stage stage = Stage.CHUNKS;

    ReoriginReplayPlan(List<ChunkReplayCache.ReplayChunk> chunks,
                       List<EntityReplayCache.ReplayPacket> entities,
                       List<GlobalStateReplayCache.Snapshot> globalState) {
        this.chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        this.entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        this.globalState = List.copyOf(Objects.requireNonNull(globalState, "globalState"));
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
        stage = Stage.COMPLETE;
        return globalState;
    }

    synchronized Stage stage() { return stage; }
    synchronized boolean complete() { return stage == Stage.COMPLETE; }
    int chunkCount() { return chunks.size(); }
    int entityPacketCount() { return entities.size(); }
    int globalStatePacketCount() { return globalState.size(); }

    private void requireStage(Stage expected) {
        if (stage != expected) {
            throw new IllegalStateException("Replay stage is " + stage + ", expected " + expected);
        }
    }
}
