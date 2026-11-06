# Phase 2: live re-origin and Geyser state replay design

Status: partial implementation. Packet snapshotting, bounded chunk/update capture, packet reconstruction, replay dispatch, paired temporary/restore respawn packet construction, and a timeout-bounded acknowledgement gate for the two-respawn sequence are implemented. The reset injection helper is available, but automatic reset orchestration and continuous live re-origin remain disabled.

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
2. **Block overlays (partially implemented):** latest single-block updates, section-block updates (expanded into individual block snapshots), and block-entity updates are captured after the base chunk. A newer base chunk invalidates older overlays. Block events and block-destruction state are not captured yet. Overlay replay is implemented in the packet reconstruction path and follows each base chunk; it is not yet triggered by a synthetic world reset.
3. **Entities (partial capture/replay implemented):** bounded per-entity snapshots now retain spawn, latest metadata, attributes, equipment, effects, motion, absolute position sync, links and passengers. Entity removals and individual effect removals clear matching records. Replay sends all nearby spawns before dependent state packets. Relative movement deltas, head rotation, animation, entity events, and several specialized entity packets are not yet folded into a complete latest-state snapshot; entity replay therefore remains disabled in the reset transaction.
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
- `JavaPacketTransform` captures chunk packets before coordinate rewriting, and captures later block/section/block-entity/light/biome updates under their real chunk coordinates.
- `EntityReplayCache` keeps a separately bounded per-session entity snapshot budget (up to 512 entities and one quarter of the configured chunk byte budget, with a 1 MiB floor). It captures spawn, metadata, attributes, equipment, effects, motion, absolute position sync, links and passengers; entity/effect removals clear stale entries. Replay is nearest-first and sends all selected spawns before dependent state packets.
- `syntheticResetRespawns` builds a temporary-world-name respawn followed by the original-world-name respawn, preserving the current dimension and shifted death marker. The hook can inject both packets directly into Geyser's inbound pipeline, bypassing the normal transform/cache capture path.
- The chunk and entity caches warm from the beginning of the Java session and retain recent state under per-player bounds. `JavaPacketTransform.replayCachedChunks` reconstructs a bounded nearest-first batch using the MCProtocolLib packet codecs, transforms it into current local coordinates, and appends captured overlays after each base chunk. `JavaSessionHook` can inject such a batch into Geyser's inbound pipeline on the downstream channel event loop. This dispatch is not yet called by a reset transaction.
- Chunk byte accounting counts stored compressed/uncompressed payload arrays. Overlay accounting adds payload bytes plus a fixed per-entry estimate. This is a bounded accounting budget, not an exact JVM heap measurement.

## Safety gates before enabling

- No chunk or entity replay until bounded memory accounting and eviction tests pass.
- The acknowledgement gate suppresses the two expected `ServerboundPlayerLoadedPacket` responses and the exact reserved teleport ID. If the reset is aborted or times out after injection, the downstream channel is closed rather than allowing late synthetic acknowledgements to leak to the real server. EmbeddedChannel tests cover unrelated acknowledgements, normal packets, injection order, and fail-closed abort. End-to-end synthetic reset sequencing is still required before use.
- No movement unfreeze while a replay batch is incomplete.
- Any timeout, disconnect, missing acknowledgement, or replay overflow aborts the transaction without forwarding synthetic packets to the backend.
- origin-shift.shift-while-moving remains false by default until full CI coverage and manual acceptance are available.