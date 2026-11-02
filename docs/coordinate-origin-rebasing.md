# Coordinate Origin Rebasing — Architecture Draft

Status: design draft on `feature/coordinate-origin-rebasing`. No gameplay packet transformations have been implemented yet.

## Goal

Prevent large Java world coordinates from losing precision before Geyser converts them into Bedrock coordinate representations.

The extension will use a per-Bedrock-session horizontal floating origin. Java remains in real world coordinates; Geyser's world-facing state for that Bedrock session is localized; Bedrock receives and reports local coordinates.

## Coordinate spaces

- **Java world space:** authoritative coordinates used by the Java server.
- **Geyser/Bedrock session space:** local coordinates used by Geyser translators, caches, entity tracking, collision and interaction logic for this session.
- **Bedrock wire space:** local coordinates encoded in Bedrock packets.

For horizontal axes:

- Java -> Geyser: `localX = realX - originX`, `localZ = realZ - originZ`.
- Geyser -> Java: `realX = localX + originX`, `realZ = localZ + originZ`.

Perform arithmetic in double precision wherever packet fields support it, before any conversion to float. Integer block/chunk coordinates require exact integer translation; chunk-aligned origins should be multiples of 16. Y is unchanged.

## Required pipeline placement

1. Intercept Java serverbound-to-Geyser packets **before Geyser translators and world/cache logic**. Localize every supported absolute world coordinate before Geyser can narrow it to float.
2. Let Geyser operate consistently in the localized coordinate space, including chunks, block updates, block entities, entities, sounds/effects, spawn data, and world-related caches.
3. Intercept Geyser-generated Java clientbound packets **after Geyser translation but before the Java backend receives them**. Restore the origin to absolute coordinates.
4. Do not shift relative vectors, deltas, rotations, or other non-world-position values.
5. Bedrock outbound packets should already be in local space; avoid a second post-Geyser subtraction layer.

## Origin lifecycle

- Keep one immutable origin snapshot for each packet transformation.
- Establish the initial origin from the first authoritative absolute player position before dependent chunk/entity packets are translated.
- Align X/Z origin to chunk boundaries.
- On origin changes, treat the transition as a coordinated session-space rebase: pause or sequence affected traffic, update all relevant state, and ensure old-origin packets cannot be mixed with new-origin packets.
- Reset/reinitialize correctly on dimension changes, respawn, backend switches, and reconnects.
- Never infer that a packet is absolute solely from its magnitude. Use packet semantics and relative flags.

## Packet audit checklist

Audit all Java -> Geyser absolute coordinate fields, including:
- player position/teleport packets (respect per-axis relative flags);
- chunk coordinates and embedded block-entity coordinates/NBT;
- block and section-block updates;
- entity spawn and absolute teleport/move packets;
- block-entity updates;
- world spawn, border, sound, particle, level-event, map/marker and other world-position payloads.

Audit all Geyser -> Java absolute coordinate fields, including:
- player and vehicle movement;
- block use, digging, interaction and item-use positions;
- entity interaction/position packets where applicable;
- block-entity NBT edits and other absolute position payloads.

Relative deltas, velocity, direction, rotation, and local offsets must not receive the world origin.

## Important integration gate

The Java-side handler must be installed before the first coordinate-bearing Java packet is translated. Geyser documents `SessionJoinEvent` as occurring after the session is connected to a Java remote server and play-ready; therefore this event must not be assumed early enough without verifying packet ordering. Determine a safe earlier hook point or a pre-translation installation mechanism before implementing the data path.

## Implementation stages

1. Map Geyser 2.12.1 packet pipeline and establish a reliable early Java-channel hook.
2. Add a small, independently tested coordinate transform model (double positions, integer block positions, chunk positions, relative-axis flags).
3. Implement a minimal vertical slice: initial player position + chunk coordinates + Bedrock movement round-trip.
4. Expand packet coverage incrementally, with explicit packet-type tests.
5. Add lifecycle/rebase sequencing and only then restore optional features such as action bar and chunk resend/cache if they remain necessary.
6. Build and test offline first. Do not test against the live world until the extension can fail closed and the user has a backup/test world.

## Non-goals

- Do not modify Java server world data or Java server coordinate behavior.
- Do not change every Geyser float to double as a first approach.
- Do not localize only the player while leaving chunks, entities, block entities, or caches in real coordinates.
- Do not claim full correctness from compilation alone; require live integration validation.
