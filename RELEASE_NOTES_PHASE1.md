# RealCoords Phase 1 — Test Release Notes

**Status:** Phase 1 test release  
**Source branch:** `feat/java-layer-origin-shift`  
**Source commit:** `f861ed326bf73e6a38efc7c1264dfbb28c45d05e`  
**CI:** [Build #342 — passed](https://github.com/GOD12345000/RealCoords/actions/runs/42184854276)  
**Target:** Geyser 2.12.1, Java 21, Gradle 8.10.2

## What Phase 1 provides

- Java protocol-layer floating-origin translation before Geyser's Java packet translators convert coordinate values.
- Per-player and per-dimension X/Z offsets, with origin selection on login, respawn/dimension changes, and qualifying distant absolute teleports.
- Reverse translation for supported serverbound movement, vehicle, block interaction, editor, and teleport acknowledgement packets.
- Coordinate translation for supported chunks, light/biomes, block and block-entity updates, entities and other world packets.
- Real-coordinate ActionBar support, Java ActionBar merging, legacy Bedrock-layer mode, and movement safety checks.
- Bounded packet/chunk capture groundwork retained for Phase 2.

## Fixed / covered in this phase

- Prevents supported distant Java coordinates from reaching Geyser's float conversion at their original huge magnitude.
- Keeps Java backend coordinates real while Bedrock/Geyser use shifted coordinates.
- Adds serverbound coordinate restoration for player and vehicle movement and teleport acknowledgements.
- Adds unit coverage for origin alignment/state, packet transforms, movement safety, packet snapshots, cache limits/overlays, and Netty pipeline behavior.

## Maintainer-reported in-game validation

The maintainer reports that the current Phase 1 build has been tested around **X=23,000,000 / Z=17,000,000** and that the tested gameplay currently has no observed issues. This is a report of the tested setup and scenarios, not a guarantee for every server version or gameplay feature.

## Not included yet — Phase 2

- Automatic re-origin while continuously walking or flying across the configured threshold.
- A complete safe transition sequence that freezes coordinate-bearing traffic, changes the origin, resets Geyser's client-side state, and resumes movement.
- End-to-end cached chunk/light/biome and block-update replay after an origin change.
- Full entity state reconstruction and replay.
- Full restoration of world/session state such as border, spawn, weather, and time.
- Complete audit and coverage of every coordinate-bearing packet and nested data field.

## Compatibility and caution

- Built against Geyser 2.12.1-SNAPSHOT / extension API 2.12.1 and Java 21.
- Geyser internal packet/session APIs may change between versions.
- Back up the world and test on a staging server before production use.
- Passing CI verifies compilation and automated tests; it does not replace in-game integration testing.

## Download

Use the `RealCoords-JAR` artifact from [successful CI run #342](https://github.com/GOD12345000/RealCoords/actions/runs/42184854276). GitHub Actions artifacts expire according to the workflow retention period.
