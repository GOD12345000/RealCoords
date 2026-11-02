# Geyser Java Packet Hook Investigation

Status: source investigation only. No Java packet hook is installed by this document.

## Exact target build

- Deployed target: Geyser Velocity `2.12.1-b1277`
- Geyser source commit: `a973709`
- Geyser dependency catalog at that commit: MCProtocolLib `26.3`
- MCProtocolLib release commit examined: `b4fa8b23064bb85d7c7966cc2cd6bf369c9c1405`

The b1277 build and commit mapping is listed in the Geyser changelog. The Geyser source at `a973709` sets `mcprotocollib = "26.3"`. The MCProtocolLib `26.3 release` commit was examined directly for listener and channel behavior.

A later Geyser source snapshot was also consulted during the initial investigation, but implementation decisions below are now grounded in the exact b1277 source and the corresponding MCProtocolLib 26.3 release source.

## Verified Geyser lifecycle

### 1. SessionInitializeEvent

Geyser fires `SessionInitializeEvent` after Bedrock authentication/session setup. This is an early Bedrock-session event, but the Java downstream session has not yet been constructed.

### 2. SessionLoginEvent

In Geyser commit `a973709`, `GeyserSession.connectDownstream()` performs these operations in order:

1. Constructs and fires `SessionLoginEvent`.
2. Applies the selected remote server and cookies.
3. Creates either `LocalSession` or `ClientNetworkSession`.
4. Wraps it in `DownstreamSession`.
5. Adds MCProtocolLib's `ClientListener`.
6. Adds Geyser's `GeyserSessionAdapter`.
7. Calls `downstream.connect(false)`.

Therefore, during the synchronous `SessionLoginEvent` callback, `session.getDownstream()` is not yet assigned. A direct Netty pipeline insertion from that callback cannot work.

### 3. SessionJoinEvent

Geyser fires `SessionJoinEvent` after the Bedrock client reports its local player initialized. By then Geyser has already processed the Java login/configuration/game entry sequence and has sent initial world state to Bedrock. It is too late to guarantee interception of the first Java world packets.

## Verified MCProtocolLib 26.3 behavior

### Listener dispatch

`NetworkSession` maintains a `CopyOnWriteArrayList<SessionListener>`.

- `addListener` appends to the list.
- `removeListener` removes the specified listener.
- `callPacketReceived` iterates listeners in list order and invokes `packetReceived(session, packet)`.
- `callEvent` iterates the same list in order for events such as `ConnectedEvent` and `PacketSendingEvent`.
- `PacketSendingEvent` supports replacing the packet through `setPacket`.

Geyser deliberately adds listeners in this order:

1. MCProtocolLib `ClientListener`
2. Geyser `GeyserSessionAdapter`

The first listener manages login/protocol state transitions. The second performs Geyser packet translation in `packetReceived` by calling `Registries.JAVA_PACKET_TRANSLATORS.translate(...)`.

A RealCoords listener must execute after `ClientListener` has handled state transitions, but before `GeyserSessionAdapter` translates the packet. Outbound packet transformation can use `PacketSendingEvent.setPacket`, provided the listener is ordered before the packet is serialized.

### Threading caveat

MCProtocolLib's `Packet.shouldRunOnGameThread()` defaults to `false`. Packets returning `true` are dispatched through the supplied packet-handler executor; other packets are dispatched on the Netty channel thread.

Consequently, modifying the listener list from Geyser's tick EventLoop is not automatically race-free: packet callbacks can also run directly on the Netty thread. A listener-order mutation must be completed before connection traffic can reach the translation stage, or use a channel-pipeline hook with well-defined ordering.

### Channel initialization

MCProtocolLib's `ClientNetworkSession.connect(false)` builds a Netty Bootstrap and calls `bootstrap.connect()`. Its `MinecraftChannelInitializer` creates the session and installs, in order, timeout, encryption, sizing, compression, flow-control, packet-codec, flush, and manager handlers.

The manager handler is the `NetworkSession` itself. A duplex handler placed immediately before the manager would see decoded inbound `Packet` objects before MCProtocolLib dispatches them, and would see outbound packets before the manager's channel context passes them to earlier outbound handlers. The existing RealCoords diagnostic approach of locating the `NetworkSession` handler and inserting before it has the right directionality once the channel exists.

## Candidate hook strategy

The best candidate is a two-stage attachment, subject to proving its timing:

1. During `SessionLoginEvent`, schedule an installation task on the Geyser session's `tickEventLoop`. The downstream object is not assigned yet, so the task must defer until it becomes available; it must not assume the downstream exists synchronously in the event callback.
2. Once the downstream `ClientSession` exists, attach a small MCProtocolLib `SessionAdapter` before the connection becomes active. Its `connected(ConnectedEvent)` callback can install the Netty duplex handler on the already initialized channel before the client/server login handshake completes. This avoids transforming handshake packets and provides a channel-event-loop installation point.
3. The adapter/handler must fail closed if it cannot be installed before the first coordinate-bearing Java play packet. It must log the installation state and the first relevant packet sequence.
4. Handle both `ClientNetworkSession` and Geyser's `LocalSession` (which extends `ClientNetworkSession`) without assuming a TCP-only implementation.

This is a candidate, not yet an approved implementation. The remaining uncertainty is the race between the scheduled installation task and `downstream.connect(false)` / `ConnectedEvent`. We must verify whether the adapter can always be registered before `ConnectedEvent`, including local-channel and authentication completion paths. If it cannot, this strategy must not be used as a guaranteed early hook.

## Important constraints

- Do not use `SessionJoinEvent` as the initial hook.
- Do not assume `SessionLoginEvent` has a ready downstream channel.
- Do not assume all packets are dispatched on the Geyser tick EventLoop.
- Do not mutate the listener list concurrently with packet dispatch.
- Do not start coordinate transformation until the first-packet ordering is proven.
- Preserve the RealCoords dedicated logger; do not move its diagnostics into Velocity's console.

## Current implementation on the branch

The branch has a standalone immutable `CoordinateTransform` model and JUnit 5 tests for double-precision localization, round-trip behavior, integer block/chunk translation, alignment validation, and overflow. The model is not yet wired into packet processing.
