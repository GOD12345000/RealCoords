# RealCoords 2.0.1

**Release type:** Patch release  
**Previous version:** 2.0.0  
**Release tag:** `v2.0.1`  
**Release commit:** `18b377d73fea011bf062b6ad7d59edb5fb2f64dd`  
**Build target:** Geyser 2.12.1-SNAPSHOT / Extension API 2.12.1  
**Runtime:** Java 21

## What's changed in 2.0.1

This patch specifically fixes the Debug Log setting not controlling all Java-layer diagnostic INFO messages.

### Fixed

- The detailed Java coordinate-packet diagnostic window and packet-count messages now respect `safety.debug-log`.
- First clientbound and serverbound packet diagnostic messages are suppressed when Debug Log is disabled.
- Java origin-change and replay-completed diagnostic INFO messages are also suppressed when Debug Log is disabled.
- Enabling **Safety → Debug Log** still makes these diagnostics available for troubleshooting.
- Safety-related WARN and ERROR messages remain visible even when Debug Log is disabled.

### Regression coverage

Tests were added or updated to verify:

- Debug Log is disabled by default.
- Explicitly enabling Debug Log preserves diagnostic output.
- Diagnostic INFO messages are suppressed when the setting is off.
- Safety warnings are not hidden by the Debug Log setting.

The release-preparation CI build and tests passed:
- [Build #417 — release preparation](https://github.com/GOD12345000/RealCoords/actions/runs/42587036545)
- [Release workflow — build and publish job](https://github.com/GOD12345000/RealCoords/actions/runs/42588081030)

## What did not change

This is a logging/configuration patch only. It does not add new coordinate packet coverage, change floating-origin calculations, or complete the continuous re-origin/world replay implementation.

## Compatibility

- Geyser build dependency: 2.12.1-SNAPSHOT / Extension API 2.12.1.
- Java bytecode target: Java 21.
- Gradle: 8.10.2.

## Known limitations

Version 2.0.1 does **not** claim that continuous walking/flying re-origin or the complete world replay pipeline is fully validated. The following still require implementation work or end-to-end validation:

- The complete Geyser client-side reset sequence for a same-dimension origin change.
- Complete replay of chunks, light, biomes, block updates, block entities, entities, and world state.
- Full auditing of synthetic responses and real packets during every transition state.
- In-game integration tests across supported Bedrock, Geyser, proxy, and backend combinations.

Keep `origin-shift.shift-while-moving: false` on production servers. Do not enable it based only on this patch release or a green CI run.

## Installation

1. Download `RealCoords-2.0.1.jar` from the [v2.0.1 GitHub Release](https://github.com/GOD12345000/RealCoords/releases/tag/v2.0.1).
2. Place the JAR in the Geyser extensions directory.
3. Restart Geyser / the proxy completely.
4. Validate on a backed-up staging world before using it on a production server.

See the [README](https://github.com/GOD12345000/RealCoords/blob/main/README.md) for installation details, configuration, current implementation status, and known limitations.
