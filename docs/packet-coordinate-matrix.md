# Bedrock packet coordinate coverage (Geyser 2.12.1 target)

This document records the outbound packet coordinate schemas handled by RealCoords.
It is intentionally specific to the Bedrock protocol types used by the pinned Geyser
2.12.1 dependency. Re-audit it when upgrading Geyser / Cloudburst Protocol.

## Newly covered in phase 1

| Packet | Absolute world-coordinate fields | Fields intentionally not shifted |
| --- | --- | --- |
| `UpdateSubChunkBlocksPacket` | packet `position`; every `BlockChangeEntry.position` in standard and extra lists | block runtime IDs, flags, entity IDs |
| `SubChunkPacket` | `centerPosition` | each `SubChunkData.position` is a local sub-chunk offset |
| `LevelEventGenericPacket` | event-specific NBT schemas below | unrelated NBT fields |
| `ClientboundMapItemDataPacket` | `origin`; block-type `MapTrackedObject.position` | `MapDecoration` x/y offsets are map-pixel offsets; entity IDs |
| `PositionTrackingDBServerBroadcastPacket` | `tag.pos[0]` and `tag.pos[2]` for UPDATE | dimension, tracking ID, Y |
| `AddHangingEntityPacket` (including `AddPaintingPacket`) | `position` | direction and IDs |

### LevelEventGenericPacket schemas

Only known Geyser-generated event schemas are transformed:

- `PARTICLE_BLOCK_EXPLOSION`: NBT `originX` and `originZ`.
- `PARTICLE_VIBRATION_SIGNAL`: `origin.x/z` and `target.x/z` inside vec3 compounds.
- `PARTICLE_CREAKING_HEART_TRIAL`: `CreakingX/Z` and `HeartX/Z`.

Do not recursively shift every NBT key named x/z: those names can represent non-world
coordinates or values that Geyser has already converted.

## Existing coverage in ShiftSession

The main session path already transforms chunk coordinates, block positions, block-entity
positions/NBT, player and entity positions, sound/particle positions, spawn positions,
container positions, and selected entity metadata positions.

## Invariants

1. Java/Geyser coordinates remain the canonical real-world coordinates.
2. Only absolute world positions are shifted.
3. Relative vectors, local sub-chunk offsets, map pixel offsets, directions, and IDs
   are not shifted.
4. Chunk coordinates use the same block-space offset converted to chunk units.
5. The packet list is not a guarantee that every future Geyser translator is covered;
   new upstream packet emission paths must be audited on dependency upgrades.
