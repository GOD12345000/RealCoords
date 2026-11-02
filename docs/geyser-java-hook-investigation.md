# Geyser Java Packet Hook Investigation

Status: investigation only. No Java packet hook is installed by this document.

## Source revision examined

- Repository: `GeyserMC/Geyser`
- Source revision: `64bb3993b4beef6743af0ab76ee5dfc8e67a0d21`
- Version in that revision's `gradle.properties`: `2.12.1-SNAPSHOT`

This is a 2.12.1 snapshot source revision, not proof that every deployed `2.12.1-b1277` binary is byte-for-byte identical. Before relying on private implementation details, compare the deployed build's source commit/build metadata.

## Verified lifecycle from this source

### 1. SessionInitializeEvent

`LoginPacketHandler.finishLogin()` fires `SessionInitializeEvent` after Bedrock authentication and session setup, before the resource-pack flow continues.

At this point the Java downstream session has not been constructed by `connectDownstream()`. This event is useful for early Bedrock-session initialization, but does not itself expose a ready Java channel.

### 2. SessionLoginEvent

`GeyserSession.connectDownstream()` performs these operations in order:

1. Constructs and fires `SessionLoginEvent`.
2. Applies the selected remote server and cookies.
3. Creates either `LocalSession` or `ClientNetworkSession`.
4. Wraps it in `DownstreamSession`.
5. Adds the MCProtocolLib `ClientListener`.
6. Adds Geyser's `GeyserSessionAdapter` after it, so protocol state changes happen before Geyser translation.
7. Calls `downstream.connect(false)`.

Therefore an extension's synchronous `SessionLoginEvent` callback runs before `session.getDownstream()` is assigned. A direct channel-pipeline insertion at this event cannot work.

### 3. SessionJoinEvent

`BedrockSetLocalPlayerAsInitializedTranslator` fires `SessionJoinEvent` only after the Bedrock client sends its local-player-initialized packet and Geyser is already logged in. The API describes it as the point where the Java remote connection is play-ready.

This is too late to guarantee interception of the first Java play-state position/chunk/entity packets. Do not use it as the initial coordinate hook.

## Pipeline implication

The Java packet listener order is significant:

- MCProtocolLib's `ClientListener` handles protocol-state transitions.
- Geyser's `GeyserSessionAdapter.packetReceived(...)` then calls `Registries.JAVA_PACKET_TRANSLATORS.translate(...)`.

The coordinate transform must run before Geyser's translator callback for Java-to-Geyser packets. For Geyser-generated Java-bound packets, the reverse transform must run after Geyser has created the Java packet but before MCProtocolLib serializes/sends it.

The previous diagnostic implementation's Netty handler positioned before the `NetworkSession` handler is directionally appropriate for decoded packet objects, but its installation on `SessionJoinEvent` is too late for initial world data.

## Candidate early-hook idea — NOT YET APPROVED

A possible experiment is to schedule a task from `SessionLoginEvent` onto `GeyserSession.getTickEventLoop()`, then attach as soon as the downstream channel exists. This must not be implemented as a blind polling loop.

Before accepting it, verify all of the following against the exact deployed Geyser/MCProtocolLib build:

- Whether `SessionLoginEvent` always runs on the same event loop as `tickEventLoop`, including Microsoft-auth completion and server-transfer paths.
- Whether the downstream channel/pipeline exists before the scheduled callback runs.
- Whether the handler can be installed before the first TCP connect/registration/read event and before any Java packet reaches `GeyserSessionAdapter`.
- Whether both TCP `ClientNetworkSession` and in-process `LocalSession` use a compatible hook.
- Whether the handler sees decoded MCProtocolLib packet objects in both inbound and outbound directions.
- Whether it can safely fail closed if attachment is late or impossible.

A timing test must prove ordering, not merely show that the handler eventually appears in the pipeline. If the first packet can win the race, this design is rejected.

## Current implementation added

The branch now contains a standalone immutable `CoordinateTransform` model:

- double-precision absolute X/Z conversion;
- exact integer block and chunk conversion;
- chunk-aligned immutable origins;
- explicit finite/range/alignment validation;
- integer overflow detection rather than silent wraparound.

JUnit 5 tests cover large-coordinate localization, round-trip behavior, block/chunk translation, invalid origins, and overflow. This model is deliberately not wired into packet processing yet.

## Next gate

Obtain or identify the exact source/build metadata for the deployed Geyser `2.12.1-b1277` and its MCProtocolLib dependency. Then implement a deterministic hook-ordering test or a supported pre-translation extension point. Only after that passes should packet transformation begin.
