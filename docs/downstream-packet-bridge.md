# Passive Java Downstream Packet Bridge

Status: implementation added; CI validation against the patched Geyser API is in progress. No coordinate fields are modified.

## Initialization order

Geyser fires `DownstreamSessionInitializeEvent` after constructing/configuring the MCProtocolLib client session and before registering `ClientListener` or `GeyserSessionAdapter`.

RealCoords registers a `SessionAdapter` during that callback. It is therefore the first MCProtocolLib listener. When `ConnectedEvent` fires, the RealCoords listener runs before `ClientListener.connected()` sends the initial handshake and login-start packets.

## Netty pipeline installation

The adapter verifies all of the following on the channel event loop:

- The channel is active.
- The packet codec handler (`NetworkConstants.CODEC_NAME`) exists.
- The manager handler (`NetworkConstants.MANAGER_NAME`) exists and is the same object as the MCProtocolLib session.
- The RealCoords handler name is unused, or already belongs to the same bridge type.

It then inserts `realcoords-downstream-packet-bridge` immediately before the manager handler.

At that location the bridge observes decoded inbound MCProtocolLib `Packet` objects before manager dispatch and outbound packet objects before the manager passes them to the codec. The first inbound and outbound packet class names are written at debug level.

Any failed precondition closes the Java downstream session. There is no late-install fallback.

## Dedicated logging

RealCoords uses its own rotating file logger under the extension data directory:

`logs/realcoords-%g.log`

- Four rotating files, each up to 4 MiB.
- UTF-8 encoding.
- Parent handlers disabled; ordinary diagnostics do not go to the Velocity/Geyser console.
- Console output is reserved for the exceptional case where the dedicated logger itself cannot initialize.

## Scope

This is an observation-only bridge. It does not rewrite packets, shift positions, alter chunks, or change any Geyser state. It exists to validate interception placement before coordinate transformation work begins.

## Remaining runtime validation

- Confirm first outbound handshake is observed for TCP sessions.
- Confirm first inbound server packet is observed for TCP sessions.
- Repeat both checks for Geyser `LocalSession`.
- Confirm reconnect and server transfer create one bridge per downstream channel.
- Confirm the dedicated log remains separate from Velocity console output.
- Confirm failure paths close the session cleanly.

Passing compilation does not replace these runtime tests.
