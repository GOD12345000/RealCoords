# RealCoords 2.0.1 — Release Notes

**Release type:** Patch release  
**Build target:** Geyser 2.12.1-SNAPSHOT, Extension API 2.12.1  
**Java:** 21  
**Gradle:** 8.10.2  
**Base release:** 2.0.0

## Fixes

- Fixes Java packet diagnostic INFO logs ignoring the **Safety → Debug Log** setting.
- With Debug Log disabled (the default), detailed coordinate packet diagnostics, first-packet diagnostics, origin-change diagnostics, and replay-completion diagnostics are suppressed.
- With Debug Log enabled, those diagnostic messages remain available.
- Safety-related WARN and ERROR messages remain visible regardless of the Debug Log setting.
- Adds tests covering the default-off setting, explicit enablement, diagnostic suppression, and preservation of safety warnings.

## Compatibility

- Build dependency: Geyser 2.12.1-SNAPSHOT / Extension API 2.12.1.
- Java bytecode target: Java 21.
- This is a focused logging/configuration fix; it does not change the floating-origin or world replay implementation.

## Existing limitations

Version 2.0.1 does **not** claim that continuous walking/flying re-origin or the complete world replay pipeline is fully validated.

The following remain incomplete or require end-to-end validation:

- Full Geyser client-side reset sequence for a same-dimension origin change.
- Complete chunk, light, biome, block update, block entity, entity, and world-state replay.
- Complete audit of synthetic responses and real packets during every transition state.
- In-game integration tests across supported Bedrock, Geyser, proxy, and backend combinations.

Do not enable `origin-shift.shift-while-moving` on a production server based solely on this release or a green CI run.

## Installation

Download `RealCoords-2.0.1.jar` from the Assets section of this GitHub Release and place it in the Geyser extensions directory. Back up your world and validate on a staging server before production use.

See [README](https://github.com/GOD12345000/RealCoords/blob/main/README.md) for configuration, installation, current status, and known limitations.
