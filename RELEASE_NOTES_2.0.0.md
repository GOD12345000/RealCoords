# RealCoords 2.0.0 — Release Notes

**Release type:** Major version / experimental integration release  
**Build target:** Geyser 2.12.1-SNAPSHOT, Extension API 2.12.1  
**Java:** 21  
**Gradle:** 8.10.2  
**Main merge commit:** `c8e7aec7293547c25916665117cdf22f658eed4f`  
**Main CI:** [Build #412 — passed](https://github.com/GOD12345000/RealCoords/actions/runs/42580739373)

## Highlights

- Consolidates the Java protocol-layer floating-origin implementation from Phase 1.
- Includes the Phase 2 transition state-management and packet deferral/gate groundwork merged into `main`.
- Keeps `origin-shift.shift-while-moving` disabled by default.
- Updates the project, Gradle artifact, and Geyser extension metadata version to `2.0.0`.

## What this release does not claim

Version 2.0.0 does **not** mean continuous walking/flying re-origin is fully validated or that the complete world replay pipeline is finished.

The following remain incomplete or require end-to-end validation:

- Full Geyser client-side reset sequence for a same-dimension origin change.
- Complete chunk, light, biome, block update, block entity, entity, and world-state replay.
- Complete audit of synthetic responses and real packets during every transition state.
- In-game integration tests across supported Bedrock, Geyser, proxy, and backend combinations.

Do not enable `origin-shift.shift-while-moving` on a production server based solely on this release or a green CI run.

## Compatibility

- Build dependency: Geyser 2.12.1-SNAPSHOT / Extension API 2.12.1.
- Java bytecode target: Java 21.
- The maintainer has reported a successful test scenario on Velocity + Geyser 2.12.1 at approximately X=23,000,000 / Z=17,000,000. This report applies only to the tested setup and scenarios; it is not a general compatibility guarantee.

## Installation

Download `RealCoords-2.0.0.jar` from the Assets section of this GitHub Release and place it in the Geyser extensions directory. Back up your world and validate on a staging server before production use.

See [README](https://github.com/GOD12345000/RealCoords/blob/main/README.md) for configuration, installation, current status, and known limitations.
