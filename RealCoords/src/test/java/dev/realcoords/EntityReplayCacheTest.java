package dev.realcoords;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.cloudburstmc.math.vector.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityReplayCacheTest {
    @Test
    void snapshotsLatestEntityStateInDependencyOrder() {
        EntityReplayCache cache = new EntityReplayCache(8, 16_384);
        ClientboundAddEntityPacket spawn = new ClientboundAddEntityPacket(
                10, UUID.randomUUID(), EntityType.ZOMBIE, 23000000, 64, -17000000,
                0, 0, 0);
        ClientboundSetEntityDataPacket metadata = new ClientboundSetEntityDataPacket(10, new org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata[0]);
        ClientboundUpdateMobEffectPacket effect = new ClientboundUpdateMobEffectPacket(
                10, Effect.SPEED, 1, 200, false, true, true, false);

        assertTrue(cache.put(10, EntityReplayCache.Kind.SPAWN, 0,
                ClientboundAddEntityPacket.class, JavaPacketSnapshot.serialize(spawn)));
        cache.updatePosition(10, spawn.getX(), spawn.getZ());
        assertTrue(cache.put(10, EntityReplayCache.Kind.METADATA, 0,
                ClientboundSetEntityDataPacket.class, JavaPacketSnapshot.serialize(metadata)));
        assertTrue(cache.put(10, EntityReplayCache.Kind.EFFECT, Effect.SPEED.ordinal(),
                ClientboundUpdateMobEffectPacket.class, JavaPacketSnapshot.serialize(effect)));

        List<EntityReplayCache.ReplayPacket> replay = cache.snapshotNearestFirst(
                23000000, -17000000, 256, 8);
        assertEquals(List.of(EntityReplayCache.Kind.SPAWN, EntityReplayCache.Kind.METADATA,
                        EntityReplayCache.Kind.EFFECT),
                replay.stream().map(EntityReplayCache.ReplayPacket::kind).toList());
        assertInstanceOf(ClientboundAddEntityPacket.class,
                JavaPacketSnapshot.deserialize(replay.get(0).packetType(), replay.get(0).payload()));
    }

    @Test
    void replacementRemovalAndByteBudgetStayBounded() {
        EntityReplayCache cache = new EntityReplayCache(2, 256);
        byte[] payload = new byte[48];
        assertTrue(cache.put(1, EntityReplayCache.Kind.SPAWN,
                0, ClientboundAddEntityPacket.class, payload));
        cache.updatePosition(1, 0, 0);
        assertTrue(cache.put(1, EntityReplayCache.Kind.METADATA,
                0, ClientboundSetEntityDataPacket.class, new byte[24]));
        long before = cache.storedBytes();
        assertTrue(cache.put(1, EntityReplayCache.Kind.METADATA,
                0, ClientboundSetEntityDataPacket.class, new byte[12]));
        assertTrue(cache.storedBytes() < before);
        assertTrue(cache.put(2, EntityReplayCache.Kind.SPAWN,
                0, ClientboundAddEntityPacket.class, payload));
        assertTrue(cache.put(3, EntityReplayCache.Kind.SPAWN,
                0, ClientboundAddEntityPacket.class, payload));
        assertTrue(cache.size() <= 2);
        assertTrue(cache.storedBytes() <= 256);
        assertTrue(cache.removeEntity(3));
        assertFalse(cache.removeEntity(3));
    }

    @Test
    void removesEntityAndEffectStateAndIgnoresEntitiesOutsideRadius() {
        EntityReplayCache cache = new EntityReplayCache(8, 4096);
        byte[] payload = {1, 2, 3};
        cache.put(1, EntityReplayCache.Kind.SPAWN, 0, ClientboundAddEntityPacket.class, payload);
        cache.updatePosition(1, 0, 0);
        cache.put(1, EntityReplayCache.Kind.EFFECT, Effect.SPEED.ordinal(),
                ClientboundUpdateMobEffectPacket.class, payload);
        cache.removeEffect(1, Effect.SPEED.ordinal());
        assertEquals(List.of(EntityReplayCache.Kind.SPAWN),
                cache.snapshotNearestFirst(0, 0, 8, 8).stream()
                        .map(EntityReplayCache.ReplayPacket::kind).toList());
        assertTrue(cache.snapshotNearestFirst(1000, 1000, 8, 8).isEmpty());
        cache.removeEntities(new int[]{1});
        assertEquals(0, cache.size());
        assertEquals(0, cache.storedBytes());
    }
}
