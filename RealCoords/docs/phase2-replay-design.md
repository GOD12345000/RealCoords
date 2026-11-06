# Phase 2: live re-origin and Geyser state replay design

Status: transaction orchestration is implemented behind the opt-in `origin-shift.shift-while-moving: false` setting. With a warm chunk cache, continuous player/vehicle movement can request an aligned origin change; the hook drops that movement packet, performs the paired synthetic respawn and local teleport, waits for the scoped Java acknowledgements, replays immutable chunk/entity/global snapshots in scheduled batches, drains buffered live world updates, and only then resumes movement. The feature remains off by default pending Bedrock dimension-acknowledgement review and in-game acceptance.

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
3. **Entities (partial capture/replay implemented):** bounded per-entity snapshots now retain spawn, latest metadata, attributes, equipment, effects, motion, absolute position sync, links and passengers. Entity removals and individual effect removals clear matching records. Replay sends all nearby spawns before dependent state packets. Relative movement deltas, relative teleports, rotation updates and absolute sync packets are folded into a synthesized absolute position snapshot. Head-only rotation, animation, entity events, and several specialized entity packets are not yet captured; entity replay therefore remains disabled in the reset transaction.
4. **Global world state (partial capture/replay implemented):** bounded latest snapshots now cover world border initialization/center/size transitions, default spawn, time, difficulty, game mode, and rain/thunder state notifications. Teams/objectives, boss bars, scoreboard state, and other plugin-specific world state still need review.
5. **Player/session state:** current real position, rotation, velocity, dimension/world identity, teleport IDs, inventory state and effect state.

## Re-origin transaction

The live transaction now follows these stages when the opt-in setting is enabled:

1. Freeze and drop only Java-bound player/vehicle movement packets; keep keep-alive, chat and unrelated control packets flowing.
2. Choose the new aligned origin from the latest accepted real position.
3. Run the verified synthetic reset path and record every synthetic response expected.
4. Inject a synthetic absolute player-position packet using a reserved teleport ID; consume only its matching acknowledgement.
5. Replay nearby chunks nearest-first in bounded batches; apply block overlays after each chunk.
6. Replay entities in dependency-safe order (spawn, metadata/attributes/equipment/effects, links/passengers, final position).
7. Restore global state and wait for the Bedrock player to acknowledge/reach the new local position.
8. Unfreeze movement only after replay completion; any timeout, buffer overflow, or replay failure closes the downstream session.

## Current implementation boundary

- `JavaPacketSnapshot` uses each MCProtocolLib packet's own serializer and ByteBuf constructor for lossless snapshots; it does not stringify or manually rebuild packet fields.
- `JavaPacketTransform` captures chunk packets before coordinate rewriting, and captures later block/section/block-entity/light/biome updates under their real chunk coordinates.
- `EntityReplayCache` keeps a separately bounded per-session entity snapshot budget (up to 512 entities and one quarter of the configured chunk byte budget, with a 1 MiB floor). It captures spawn, metadata, attributes, equipment, effects, motion, absolute position sync, links and passengers; entity/effect removals clear stale entries. Replay is nearest-first and sends all selected spawns before dependent state packets.
- `syntheticResetRespawns` builds a temporary-world-name respawn followed by the original-world-name respawn, preserving the current dimension and shifted death marker. The hook can inject both packets directly into Geyser's inbound pipeline, bypassing the normal transform/cache capture path.
- `GlobalStateReplayCache` retains bounded latest snapshots for time, difficulty, game mode/weather notifications, default spawn and world-border state. Its replay path preserves last-update order and runs each packet through the normal coordinate transform.
- `ReoriginReplayPlan` takes immutable chunk/entity/global snapshots and exposes a forward-only staged cursor: chunks first in bounded pages, then entity packets in bounded pages, then global state, and finally buffered live updates. This prevents the replay source from being reordered by cache updates that arrive after the snapshot. The hook schedules each stage on the downstream channel event loop.
- `LiveUpdateBuffer` is wired into the Java hook while a replay plan is active: selected live world/entity/scoreboard packets are withheld, snapshotted in FIFO order, and drained after global-state replay. Overflow or an unencodable packet aborts the plan and closes the downstream session rather than silently losing world state.
- The chunk, entity and global-state caches warm from the beginning of the Java session and retain recent state under per-player bounds. Replay snapshots include only chunks within the active view radius plus the configured eviction margin. `JavaPacketTransform` reconstructs bounded nearest-first batches using MCProtocolLib packet codecs, transforms them into current local coordinates, and appends captured overlays after each base chunk.
- The configured per-player byte budget is partitioned: 75% chunks/overlays, 12.5% entity snapshots, 3.125% global state, and 9.375% live-update buffering during replay. Chunk accounting counts stored compressed/uncompressed payload arrays; entity/global/live entries add payload bytes plus a fixed per-entry estimate. This is a bounded accounting budget, not an exact JVM heap measurement.

## Safety gates before enabling

- Bounded memory accounting, eviction, snapshot ordering, replay pagination and live-update buffering have automated unit coverage. Full Bedrock client/server integration remains unverified.
- The acknowledgement gate suppresses the two expected `ServerboundPlayerLoadedPacket` responses and the exact reserved teleport ID. If the reset is aborted or times out after injection, the downstream channel is closed rather than allowing late synthetic acknowledgements to leak to the real server. EmbeddedChannel tests cover unrelated acknowledgements, normal packets, injection order, and fail-closed abort. The hook waits until Geyser reports `GeyserSession.isSpawned()` after the synthetic player-position packet. In the inspected Geyser translator, the Bedrock `DIMENSION_CHANGE_SUCCESS` action is handled separately by `BedrockPlayerActionTranslator`; this Java-channel hook does not observe that Bedrock packet directly. The ordering between Bedrock's acknowledgement and chunk acceptance must therefore still be verified in an actual Bedrock session before treating the transaction as production-ready.
- Player and vehicle movement is withheld during the synthetic reset and all replay stages; it resumes only after the live-update buffer is drained.
- Synthetic-ack timeout, replay timeout, disconnect, missing acknowledgement, replay exception, or live-update buffer overflow closes the downstream session rather than forwarding synthetic acknowledgements or leaving a partially restored world.
- `origin-shift.shift-while-moving` remains false by default. Enable it only after the latest CI run passes and the synthetic dimension reset is accepted in a real Bedrock session.