package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReoriginReplayPlanTest {
    @Test
    void advancesChunksEntitiesAndGlobalStateInStrictOrder() {
        List<ChunkReplayCache.ReplayChunk> chunks = List.of(
                new ChunkReplayCache.ReplayChunk(0, 0, new byte[]{1}, List.of()),
                new ChunkReplayCache.ReplayChunk(1, 0, new byte[]{2}, List.of()),
                new ChunkReplayCache.ReplayChunk(2, 0, new byte[]{3}, List.of()));
        List<EntityReplayCache.ReplayPacket> entities = List.of(
                new EntityReplayCache.ReplayPacket(1, EntityReplayCache.Kind.SPAWN,
                        ClientboundAddEntityPacket.class, 0, new byte[]{4}),
                new EntityReplayCache.ReplayPacket(1, EntityReplayCache.Kind.POSITION,
                        ClientboundAddEntityPacket.class, 0, new byte[]{5}));
        List<GlobalStateReplayCache.Snapshot> global = List.of(
                new GlobalStateReplayCache.Snapshot(GlobalStateReplayCache.Key.TIME,
                        ClientboundGameEventPacket.class, new byte[]{6}));
        ReoriginReplayPlan plan = new ReoriginReplayPlan(chunks, entities, global);

        assertEquals(ReoriginReplayPlan.Stage.CHUNKS, plan.stage());
        assertEquals(List.of(0, 1), plan.nextChunkBatch(2).stream()
                .map(ChunkReplayCache.ReplayChunk::x).toList());
        assertEquals(ReoriginReplayPlan.Stage.CHUNKS, plan.stage());
        assertEquals(List.of(2), plan.nextChunkBatch(2).stream()
                .map(ChunkReplayCache.ReplayChunk::x).toList());
        assertEquals(ReoriginReplayPlan.Stage.ENTITIES, plan.stage());
        assertEquals(1, plan.nextEntityBatch(1).size());
        assertEquals(ReoriginReplayPlan.Stage.ENTITIES, plan.stage());
        assertEquals(1, plan.nextEntityBatch(1).size());
        assertEquals(ReoriginReplayPlan.Stage.GLOBAL_STATE, plan.stage());
        assertEquals(global, plan.finishGlobalState());
        assertTrue(plan.complete());
    }

    @Test
    void emptyStagesAdvanceWithoutProducingPackets() {
        ReoriginReplayPlan plan = new ReoriginReplayPlan(List.of(), List.of(), List.of());
        assertTrue(plan.nextChunkBatch(8).isEmpty());
        assertEquals(ReoriginReplayPlan.Stage.ENTITIES, plan.stage());
        assertTrue(plan.nextEntityBatch(8).isEmpty());
        assertEquals(ReoriginReplayPlan.Stage.GLOBAL_STATE, plan.stage());
        assertTrue(plan.finishGlobalState().isEmpty());
        assertTrue(plan.complete());
    }

    @Test
    void rejectsOutOfOrderAndInvalidBatchRequests() {
        ReoriginReplayPlan plan = new ReoriginReplayPlan(List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> plan.nextChunkBatch(0));
        assertThrows(IllegalStateException.class, () -> plan.nextEntityBatch(1));
        plan.nextChunkBatch(1);
        plan.nextEntityBatch(1);
        plan.finishGlobalState();
        assertThrows(IllegalStateException.class, () -> plan.finishGlobalState());
    }
}
