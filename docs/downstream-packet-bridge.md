# Passive Java Downstream Packet Bridge

Status: implemented and compile-validated against the pinned patched Geyser API. Runtime TCP / LocalSession integration checks are still pending. No coordinate fields are modified.

## Initialization order

Geyser fires `DownstreamSessionInitializeEvent` after constructing/configuring the MCProtocolLib client session and before registering `ClientListener` or `GeyserSessionAdapter`.

RealCoords registers a `SessionAdapter` during that callback. It is therefore the first MCProtocolLib listener. When `ConnectedEvent` fires, the RealCoords listener runs before `ClientListener.connected()` sends the initial handshake and login-start packets.

## Verified MCProtocolLib pipeline order

The pinned MCProtocolLib channel initializer creates handlers in this order (head to tail):

```text
... -> encryption -> sizer -> compression -> flow-control
    -> codec -> flush-handler -> manager
```

RealCoords inserts its duplex handler immediately before `manager`:

```text
... -> codec -> flush-handler -> realcoords-bridge -> manager
```

This placement is intentional:

- **Inbound:** Netty traverses toward the tail. The codec first decodes bytes into MCProtocolLib `Packet` objects; the bridge then sees those decoded packets before the manager dispatches them.
- **Outbound:** Netty traverses outbound handlers toward the head. A packet written by the session reaches the bridge before the flush handler and codec encode it into bytes.

The bridge therefore sees packet objects in both directions, not raw encrypted/compressed bytes. It must not be moved to the other side of the codec without revisiting these assumptions.

## Installation checks

The adapter verifies all of the following on the channel event loop:

- The channel is active.
- The packet codec handler (`NetworkConstants.CODEC_NAME`) exists.
- The manager handler (`NetworkConstants.MANAGER_NAME`) exists and is the same object as the MCProtocolLib session.
- The RealCoords handler name is unused, or already belongs to the same bridge type.

It then inserts `realcoords-downstream-packet-bridge` immediately before the manager handler.

The first inbound and outbound packet class names are written at debug level. Any failed precondition closes the Java downstream session. There is no late-install fallback.

## Dedicated logging

RealCoords uses its own rotating file logger under the extension data directory:

`logs/realcoords-%g.log`

- Four rotating files, each up to 4 MiB.
- UTF-8 encoding.
- Parent handlers disabled; ordinary diagnostics do not go to the Velocity/Geyser console.
- Console output is reserved for the exceptional case where the dedicated logger itself cannot initialize.

## Scope

This Netty bridge remains observation-only. It does not call the pure helpers in `PlayerMovementPacketTransform`, rewrite packets, shift positions, alter chunks, or change any Geyser state. The separate pure transformation class now covers a growing set of player, entity, vehicle, chunk, block-event, and block-interaction packet coordinates, with unit tests. It is intentionally not connected to this bridge until origin lifecycle and full world-state consistency are established.

## Remaining runtime validation

- Confirm first outbound handshake is observed for TCP sessions.
- Confirm first inbound server packet is observed for TCP sessions.
- Repeat both checks for Geyser `LocalSession`.
- Confirm reconnect and server transfer create one bridge per downstream channel.
- Confirm the dedicated log remains separate from Velocity console output.
- Confirm failure paths close the session cleanly.

Passing compilation does not replace these runtime tests.
