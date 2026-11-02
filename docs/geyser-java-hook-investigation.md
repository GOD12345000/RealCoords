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

This is a candidate, not yet an approved implementation. The remaining uncertainty is the race between the scheduled installation task and `downstream.connect(false)` / `ConnectedEvent`.

Additional source facts:

- `GeyserServerInitializer.initSession()` creates the Geyser session with `this.eventLoopGroup.next()`. This is Geyser's per-session tick EventLoop; it must not be assumed to be the Bedrock channel's event loop.
- `GeyserSession.executeInEventLoop()` always queues work on that tick EventLoop. `ensureInEventLoop()` runs inline only when the caller is already on that exact EventLoop.
- The Java downstream is created and `connect(false)` is called synchronously after `SessionLoginEvent` returns. The Netty connection and the Geyser tick EventLoop can progress independently.

Therefore, a task queued from `SessionLoginEvent` is a plausible way to observe the now-created downstream, but it is not a proven guarantee that the task executes before `ConnectedEvent`. The installer must attach an early listener before connection activation or detect that activation already occurred and fail closed. Do not treat a successful eventual pipeline lookup as proof that the first-packet boundary was met.

The proposed listener-ordering mechanism also needs care: `NetworkSession` uses `CopyOnWriteArrayList`, so reordering listeners during a callback affects subsequent dispatch iterations, not the already-created iteration snapshot. A `ConnectedEvent` callback can potentially reorder the list for later packet events, but only if the custom adapter was registered before that event begins.

We must verify whether the adapter can always be registered before `ConnectedEvent`, including local-channel and authentication completion paths. If it cannot, this strategy must not be used as a guaranteed early hook.

## Important constraints

- Do not use `SessionJoinEvent` as the initial hook.
- Do not assume `SessionLoginEvent` has a ready downstream channel.
- Do not assume all packets are dispatched on the Geyser tick EventLoop.
- Do not mutate the listener list concurrently with packet dispatch.
- Do not start coordinate transformation until the first-packet ordering is proven.
- Preserve the RealCoords dedicated logger; do not move its diagnostics into Velocity's console.

## Current implementation on the branch

The branch has a standalone immutable `CoordinateTransform` model and JUnit 5 tests for double-precision localization, round-trip behavior, integer block/chunk translation, alignment validation, and overflow. The model is not yet wired into packet processing.


## Final conclusion from the exact MCProtocolLib 26.3 source

### What the channel lifecycle guarantees

`ClientNetworkSession.connect(false)` starts an asynchronous Netty Bootstrap connection. In `MinecraftChannelInitializer`, MCProtocolLib installs the packet codec and then the `NetworkSession` handler.

When the channel becomes active, `NetworkSession.channelActive()`:

1. stores the channel;
2. synchronously fires `ConnectedEvent` to its current listener list;
3. only after all ConnectedEvent listeners return, flushes queued outbound packets.

This gives a useful deterministic hook point: a listener that was registered before `connect(false)` can install a duplex handler in its `connected(ConnectedEvent)` callback. The channel is initialized, the `NetworkSession` is in the pipeline, and the initial queued handshake has not yet been flushed. The handler can be inserted immediately before the `NetworkSession` handler, so inbound decoded packets are intercepted before listener dispatch and outbound packets are intercepted before serialization.

### Why the extension API still cannot guarantee it

Geyser's current ordering in `connectDownstream()` is:

1. construct downstream;
2. register `ClientListener`;
3. register `GeyserSessionAdapter`;
4. call `downstream.connect(false)`.

But the public `SessionLoginEvent` fires before step 1. An extension cannot obtain the downstream session from that event. A task scheduled onto the Geyser tick EventLoop may eventually see it, but `ClientNetworkSession` connects on its own static Netty EventLoopGroup. There is no source-level happens-before guarantee that a scheduled extension task runs before `ConnectedEvent`. The direct-auth, asynchronous-auth-chain, and Microsoft-auth completion paths also call `connectDownstream()` from different caller contexts.

A late `ConnectedEvent` listener does not solve this: it must already be registered to receive the event. Registering it after the channel has become active is too late to guarantee that the handshake has not already started.

### Recommended deterministic integration point

The clean solution is a small Geyser API/source addition, not a polling race:

- Add a downstream-initialization event in `GeyserSession.connectDownstream()`.
- Fire it after `ClientListener` is registered and before `GeyserSessionAdapter` is registered (or expose an explicit ordered listener-registration API at this point).
- Expose the MCProtocolLib `ClientSession` through the event.
- Let RealCoords register its own `SessionAdapter` during this callback.
- In RealCoords' `connected(ConnectedEvent)`, insert the Netty duplex handler immediately before the `NetworkSession` handler.
- Fail closed if the expected handler is missing or the event occurs after connection activation.

This gives the listener a deterministic position between `ClientListener` and `GeyserSessionAdapter`, and a deterministic opportunity to install the Netty handler before queued login traffic is flushed.

The event should be documented as an internal/downstream lifecycle hook and should not expose implementation classes unnecessarily if Geyser prefers a stable API abstraction. For a narrow initial implementation, an API event exposing the MCProtocolLib `ClientSession` is the simplest workable option.

### Decision

Do not implement a tick-loop polling installer as if it were guaranteed. Do not use `SessionJoinEvent`. Do not mutate MCProtocolLib's private listener list reflectively.

The next implementation step should be to prepare a minimal Geyser patch/API proposal for the pre-connect downstream initialization event, then build RealCoords against that hook. Until that hook exists (or an equivalent deterministic mechanism is proven), packet transformation remains intentionally unwired.
