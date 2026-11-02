# Geyser Java Packet Hook Investigation

Historical source investigation. The proposed Geyser API patch below is superseded for the current branch by the standalone startup-agent design in [startup-agent.md](startup-agent.md). This file is retained as research history; it is not the current deployment procedure.

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

The key additional detail is that `ClientListener.connected(ConnectedEvent)` immediately sends the Java handshake and login-start packets. `NetworkSession.channelActive()` invokes `ConnectedEvent` listeners synchronously in registration order. Therefore a RealCoords `SessionAdapter` registered after `ClientListener` is too late: the initial outbound handshake is already sent before RealCoords gets its callback. RealCoords must be the first listener, ahead of `ClientListener`, and its `connected()` callback must install the Netty duplex handler before returning. The handler then intercepts decoded inbound packets before they reach the manager and outbound packet writes before encoding; it does not need to participate in MCProtocolLib's packet listener order.

### Threading caveat

MCProtocolLib's `Packet.shouldRunOnGameThread()` defaults to `false`. Packets returning `true` are dispatched through the supplied packet-handler executor; other packets are dispatched on the Netty channel thread.

Consequently, modifying the listener list from Geyser's tick EventLoop is not automatically race-free: packet callbacks can also run directly on the Netty thread. A listener-order mutation must be completed before connection traffic can reach the translation stage, or use a channel-pipeline hook with well-defined ordering.

### Channel initialization

MCProtocolLib's `ClientNetworkSession.connect(false)` builds a Netty Bootstrap and calls `bootstrap.connect()`. Its `MinecraftChannelInitializer` creates the session and installs, in order, timeout, encryption, sizing, compression, flow-control, packet-codec, flush, and manager handlers.

The manager handler is the `NetworkSession` itself. A duplex handler placed immediately before the manager would see decoded inbound `Packet` objects before MCProtocolLib dispatches them, and would see outbound packets before the manager's channel context passes them to earlier outbound handlers. The existing RealCoords diagnostic approach of locating the `NetworkSession` handler and inserting before it has the right directionality once the channel exists.

## LocalSession and channel-hook verification

The exact Geyser `a973709` source imports `org.geysermc.geyser.network.java.LocalSession`, not a session-auth package. That class:

- Extends MCProtocolLib `ClientNetworkSession`.
- Overrides the channel factory and event-loop group for Geyser's local-channel implementation.
- Overrides `getChannelHandler()`, but still constructs MCProtocolLib's `MinecraftChannelInitializer`.
- Calls `super.initChannel(channel)` after applying the spoofed remote address and HAProxy setup.

Therefore LocalSession uses the same MCProtocolLib `NetworkSession` handler and the same channel initialization sequence. The ordinary TCP session and Geyser's local-channel session can use the same downstream listener and Netty-pipeline strategy. This conclusion is source-verified; it is not yet a runtime integration test.

## Exact deterministic ordering

At the Geyser `a973709` call site, the existing order is:

1. Create `ClientNetworkSession` or `LocalSession`.
2. Configure downstream flags and disable MCProtocolLib's default listener registration.
3. Add MCProtocolLib `ClientListener`.
4. Add Geyser `GeyserSessionAdapter`.
5. Call `downstream.connect(false)`.

The proposed RealCoords initialization event is inserted between steps 2 and 3, allowing its listener to be registered first.

MCProtocolLib `26.3` confirms that `NetworkSession.channelActive()` stores the channel and synchronously calls `ConnectedEvent` on the registered listeners. Importantly, `ClientListener.connected()` sends the handshake and login-start packets immediately from its callback; these sends occur before `channelActive()` reaches its later pending-queue flush. The `MinecraftChannelInitializer` installs the packet codec and then the `NetworkSession` manager handler.

A custom `SessionAdapter` registered as the first listener therefore has the required guarantee:

- Its `connected(ConnectedEvent)` callback runs after the channel and manager handler exist, but before `ClientListener.connected()` sends the initial handshake.
- A duplex handler inserted immediately before `NetworkConstants.MANAGER_NAME` sees decoded inbound packet objects before MCProtocolLib listener dispatch and outbound packet objects before packet encoding.

The same reasoning applies to `LocalSession` because it inherits `ClientNetworkSession` and delegates channel initialization to the same initializer.

## Revised hook decision

The earlier tick-loop polling candidate is rejected. It has no happens-before guarantee against the independent MCProtocolLib Netty EventLoop and cannot prove registration before `ConnectedEvent`.

The deterministic design is now:

1. Add a narrow downstream-initialization hook in Geyser's `connectDownstream()` after the downstream session is created and configured, but before `ClientListener` is registered.
2. During that synchronous callback, RealCoords registers a MCProtocolLib `SessionAdapter` as the first listener on the provided downstream session.
3. In `connected(ConnectedEvent)`, RealCoords inserts the duplex packet handler immediately before the `NetworkSession` pipeline handler. This executes before `ClientListener.connected()` sends the initial handshake.
4. If the expected handler is missing, the channel is already active before registration, or insertion fails, RealCoords fails closed and reports a clear diagnostic. It must not silently fall back to late installation.

The proposed public API exposes MCProtocolLib `ClientSession`, which requires adding the existing `libs.mcprotocollib` version-catalog dependency to the API module. This intentionally adds MCProtocolLib as a published API dependency. A Geyser-owned SPI could hide MCProtocolLib and Netty types, but would need to provide equivalent ordered listener registration and channel access; that would be a larger abstraction. The current patch draft chooses the smaller direct contract, subject to review of Geyser maintainers' API compatibility preferences.

No production packet transformation should be implemented until the hook patch is built against the exact Geyser revision and tested for both TCP and LocalSession paths.

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

### Historical recommended integration point (superseded)

At the time of this investigation, the cleanest known option was a small Geyser API/source addition, not a polling race. The current branch instead uses a version-pinned startup agent so the official Geyser JAR remains unchanged:

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

The current implementation step is to build and runtime-test the startup agent against the exact Geyser 2.12.1 build, including both TCP and LocalSession, before implementing packet transformations. Until that hook exists (or an equivalent deterministic mechanism is proven), packet transformation remains intentionally unwired.
