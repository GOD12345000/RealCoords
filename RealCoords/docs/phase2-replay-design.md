# Phase 2: live re-origin and Geyser state replay design

Status: partial implementation. Packet snapshotting and bounded chunk/update capture are implemented; replay and live re-origin are still disabled.

## Verified Geyser reset behavior

The Geyser 2.12.1 JavaRespawnTranslator performs the following work for a Java respawn packet:

- Resets player metadata and attribute modifiers when the corresponding keep flags are false.
- Marks the session unspawned and clears player motion / last-tick velocity.
- Restores health attributes and clears inventory-holder / closing-inventory state.
- Updates last-death position and Bedrock player game mode.
- Resets weather when rain or thunder is active.
- If the Java dimension type or world name changes, it resets time parameters, switches dimension, and loads the new dimension.
- Sends ServerboundPlayerLoadedPacket.INSTANCE downstream after processing.

DimensionUtils.switchDimension clears these Geyser caches/state:

- Chunk cache
- Entity cache (all entities)
- Item-frame cache
- Lodestone cache
- Piston cache
- Skull cache
- Block-break handler state
- Entity effect cache (effects are sent again by the Java server)
- Rain and thunder state (reset to zero)

It also performs the Bedrock dimension transition, sets the new Java dimension, finalizes the switch, and sends empty chunks as part of the transition.

Sources inspected: GeyserMC/Geyser JavaRespawnTranslator.java and DimensionUtils.java at the source revision used for this investigation. Re-check these methods whenever the pinned Geyser dependency changes.

## Synthetic reset implications

A synthetic same-dimension respawn cannot be assumed to clear world state: JavaRespawnTranslator only calls DimensionUtils.switchDimension when the Java dimension type or world name differs. A controlled same-dimension reset therefore needs a temporary synthetic world-name transition (or another verified reset path), followed by restoration of the real world name.

The reset path emits ServerboundPlayerLoadedPacket.INSTANCE downstream. RealCoords must track and suppress the synthetic instance so it is never forwarded to the Java server. A synthetic absolute player-position packet also has a teleport ID; the corresponding ServerboundAcceptTeleportationPacket must be consumed by the hook. These suppressions must be scoped to the exact synthetic operation and expire safely; never suppress ordinary acknowledgements.

The Bedrock dimension transition acknowledgement is a Bedrock-side event handled inside Geyser, not interchangeable with Java MCProtocolLib serverbound packets. Its behavior must be verified against the Bedrock translator before introducing any additional suppression.

## Required replay inventory

The following state must be captured or explicitly proven to be re-sent after a synthetic reset:

1. **Chunks (capture implemented):** complete original `ClientboundLevelChunkWithLightPacket` snapshots keyed by real chunk X/Z, including chunk bytes, heightmaps, embedded block entities and light data. Deflate compression, LRU chunk-count eviction and a per-player byte budget are enforced. Standalone light updates and per-chunk biome packets are captured as overlays.
2. **Block overlays (partially implemented):** latest single-block updates, section-block updates (expanded into individual block snapshots), and block-entity updates are captured after the base chunk. A newer base chunk invalidates older overlays. Block events and block-destruction state are not captured yet. Overlay replay is not wired yet.
3. **Entities:** latest add/spawn packet plus latest metadata, attributes, equipment, effects, passengers, links and position/orientation. Remove all records on entity removal. Replaying only AddEntity is insufficient for mobs and item frames.
4. **Global world state:** world border, default spawn, time, weather, difficulty/game mode, teams/objectives where relevant, and other state Geyser resets or does not automatically re-request.
5. **Player/session state:** current real position, rotation, velocity, dimension/world identity, teleport IDs, inventory state and effect state.

## Re-origin transaction

The transaction is intentionally not enabled until every stage is testable:

1. Freeze and drop only Java-bound player/vehicle movement packets; keep keep-alive, chat and unrelated control packets flowing.
2. Choose the new aligned origin from the latest accepted real position.
3. Run the verified synthetic reset path and record every synthetic response expected.
4. Inject a synthetic absolute player-position packet using a reserved teleport ID; consume only its matching acknowledgement.
5. Replay nearby chunks nearest-first in bounded batches; apply block overlays after each chunk.
6. Replay entities in dependency-safe order (spawn, metadata/attributes/equipment/effects, links/passengers, final position).
7. Restore global state and wait for the Bedrock player to acknowledge/reach the new local position.
8. Unfreeze movement only after replay completion or abort safely and restore the previous origin.

## Current implementation boundary

- `JavaPacketSnapshot` uses each MCProtocolLib packet's own serializer and ByteBuf constructor for lossless snapshots; it does not stringify or manually rebuild packet fields.
- `JavaPacketTransform` captures chunk packets before coordinate rewriting, and captures later block/section/block-entity/light updates under their real chunk coordinates.
- The cache is currently observational only: it warms from the beginning of the Java session (before the first origin threshold) and retains recent chunks under the configured per-player bounds. No cached packet is injected back into Geyser yet, and no synthetic reset or continuous movement re-origin is active.
- Chunk byte accounting counts stored compressed/uncompressed payload arrays. Overlay accounting adds payload bytes plus a fixed per-entry estimate. This is a bounded accounting budget, not an exact JVM heap measurement.

## Safety gates before enabling

- No chunk or entity replay until bounded memory accounting and eviction tests pass.
- No synthetic reset until EmbeddedChannel tests prove exact response suppression and prove ordinary teleport/player-loaded packets pass through.
- No movement unfreeze while a replay batch is incomplete.
- Any timeout, disconnect, missing acknowledgement, or replay overflow aborts the transaction without forwarding synthetic packets to the backend.
- origin-shift.shift-while-moving remains false by default until full CI coverage and manual acceptance are available.