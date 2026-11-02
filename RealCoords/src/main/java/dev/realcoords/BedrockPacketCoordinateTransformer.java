package dev.realcoords;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.MapTrackedObject;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundMapItemDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventGenericPacket;
import org.cloudburstmc.protocol.bedrock.packet.PositionTrackingDBServerBroadcastPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Coordinate schemas for Bedrock packets which are not part of the common
 * movement/block packet path in ShiftSession.
 *
 * Keep this deliberately schema-specific: map decoration offsets and sub-chunk
 * local offsets are not world coordinates and must not be shifted.
 */
final class BedrockPacketCoordinateTransformer {
    private BedrockPacketCoordinateTransformer() {
    }

    static boolean transformOutbound(BedrockPacket packet, CoordinateTransform transform) {
        if (packet instanceof UpdateSubChunkBlocksPacket update) {
            update.setPosition(toBedrock(update.getPosition(), transform));
            shiftBlockChanges(update.getStandardBlocks(), transform);
            shiftBlockChanges(update.getExtraBlocks(), transform);
            return true;
        }

        if (packet instanceof SubChunkPacket subChunk) {
            // centerPosition is world-absolute. SubChunkData.position is a local
            // offset from the center and must remain unchanged.
            Vector3i center = subChunk.getCenterPosition();
            if (center != null) {
                subChunk.setCenterPosition(toBedrock(center, transform));
            }
            return true;
        }

        if (packet instanceof LevelEventGenericPacket event) {
            return shiftGenericEvent(event, transform);
        }

        if (packet instanceof ClientboundMapItemDataPacket map) {
            Vector3i origin = map.getOrigin();
            if (origin != null) {
                map.setOrigin(transform.toBedrock(origin));
            }

            List<MapTrackedObject> objects = map.getTrackedObjects();
            if (objects != null && !objects.isEmpty()) {
                List<MapTrackedObject> shifted = new ArrayList<>(objects.size());
                for (MapTrackedObject object : objects) {
                    if (object.getType() == MapTrackedObject.Type.BLOCK && object.getPosition() != null) {
                        shifted.add(new MapTrackedObject(toBedrock(object.getPosition(), transform)));
                    } else {
                        shifted.add(object);
                    }
                }
                map.setTrackedObjects(shifted);
            }
            // MapDecoration x/y offsets are map-pixel offsets, not world positions.
            return true;
        }

        if (packet instanceof PositionTrackingDBServerBroadcastPacket tracking) {
            NbtMap tag = tracking.getTag();
            if (tag != null && tracking.getAction() == PositionTrackingDBServerBroadcastPacket.Action.UPDATE) {
                List<Integer> pos = tag.getList("pos", NbtType.INT);
                if (pos.size() == 3) {
                    List<Integer> shifted = new ArrayList<>(pos);
                    shifted.set(0, transform.toBedrockX(shifted.get(0)));
                    shifted.set(2, transform.toBedrockZ(shifted.get(2)));
                    tracking.setTag(tag.toBuilder().putList("pos", NbtType.INT, shifted).build());
                }
            }
            return true;
        }

        return false;
    }

    private static Vector3i toBedrock(Vector3i position, CoordinateTransform transform) {
        return position == null ? null : Vector3i.from(
                transform.toBedrockX(position.getX()),
                position.getY(),
                transform.toBedrockZ(position.getZ())
        );
    }

    private static void shiftBlockChanges(List<BlockChangeEntry> entries, CoordinateTransform transform) {
        ListIterator<BlockChangeEntry> iterator = entries.listIterator();
        while (iterator.hasNext()) {
            BlockChangeEntry entry = iterator.next();
            iterator.set(new BlockChangeEntry(
                    toBedrock(entry.getPosition(), transform),
                    entry.getDefinition(),
                    entry.getUpdateFlags(),
                    entry.getMessageEntityId(),
                    entry.getMessageType()
            ));
        }
    }

    private static boolean shiftGenericEvent(LevelEventGenericPacket event, CoordinateTransform transform) {
        Object rawTag = event.getTag();
        if (!(rawTag instanceof NbtMap tag)) {
            return false;
        }

        String type = String.valueOf(event.getType());
        NbtMap shifted = tag;

        if ("PARTICLE_BLOCK_EXPLOSION".equals(type)) {
            if (tag.containsKey("originX", NbtType.FLOAT)) {
                shifted = shifted.toBuilder()
                        .putFloat("originX", transform.toBedrockX(tag.getFloat("originX")))
                        .putFloat("originZ", transform.toBedrockZ(tag.getFloat("originZ")))
                        .build();
            }
        } else if ("PARTICLE_VIBRATION_SIGNAL".equals(type)) {
            NbtMap origin = tag.getCompound("origin", null);
            NbtMap target = tag.getCompound("target", null);
            NbtMap updated = tag.toBuilder().build();
            if (origin != null) {
                updated = updated.toBuilder().putCompound("origin", shiftVec3(origin, transform)).build();
            }
            if (target != null) {
                updated = updated.toBuilder().putCompound("target", shiftVec3(target, transform)).build();
            }
            shifted = updated;
        } else if ("PARTICLE_CREAKING_HEART_TRIAL".equals(type)) {
            var builder = tag.toBuilder();
            boolean changed = false;
            if (tag.containsKey("CreakingX", NbtType.FLOAT)) {
                builder.putFloat("CreakingX", transform.toBedrockX(tag.getFloat("CreakingX")));
                builder.putFloat("CreakingZ", transform.toBedrockZ(tag.getFloat("CreakingZ")));
                changed = true;
            }
            if (tag.containsKey("HeartX", NbtType.FLOAT)) {
                builder.putFloat("HeartX", transform.toBedrockX(tag.getFloat("HeartX")));
                builder.putFloat("HeartZ", transform.toBedrockZ(tag.getFloat("HeartZ")));
                changed = true;
            }
            if (changed) {
                shifted = builder.build();
            }
        } else {
            return false;
        }

        event.setTag(shifted);
        return true;
    }

    private static NbtMap shiftVec3(NbtMap vector, CoordinateTransform transform) {
        if (!vector.containsKey("x", NbtType.FLOAT) || !vector.containsKey("z", NbtType.FLOAT)) {
            return vector;
        }
        return vector.toBuilder()
                .putFloat("x", transform.toBedrockX(vector.getFloat("x")))
                .putFloat("z", transform.toBedrockZ(vector.getFloat("z")))
                .build();
    }
}
