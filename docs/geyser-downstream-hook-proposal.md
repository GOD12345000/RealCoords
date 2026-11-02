# Geyser Downstream Initialization Hook Proposal

Status: proposal only. This document does not modify Geyser or enable packet transformation.

## Target

- Geyser source: `GeyserMC/Geyser@a973709` (the deployed 2.12.1-b1277 build)
- MCProtocolLib: 26.3, source commit `b4fa8b23064bb85d7c7966cc2cd6bf369c9c1405`
- RealCoords development branch: `feature/coordinate-origin-rebasing`

## Problem

Geyser's public `SessionLoginEvent` is fired before the Java downstream session is created. The public `SessionJoinEvent` is too late to guarantee interception before initial Java world packets. Polling the Geyser tick EventLoop is not deterministic relative to MCProtocolLib's independent Netty EventLoop.

At the exact target revision, Geyser creates a `ClientNetworkSession` (or `LocalSession`), registers MCProtocolLib's `ClientListener`, registers `GeyserSessionAdapter`, then calls `connect(false)`. Source inspection of `ClientListener.connected()` shows that it immediately sends the handshake and login-start packets when `ConnectedEvent` fires. The proposed event is inserted just before those two listener registrations so RealCoords can register first.

MCProtocolLib's `NetworkSession.channelActive()` fires `ConnectedEvent` synchronously before flushing queued outbound packets. However, listener ordering matters: `ClientListener.connected()` sends the handshake immediately from its own `ConnectedEvent` callback. Therefore a RealCoords adapter registered after `ClientListener` is too late for the first outbound packets. RealCoords must be registered before `ClientListener`, so its `connected()` callback installs the Netty duplex handler before the protocol listener sends the handshake.

## Proposed API

Add `api/src/main/java/org/geysermc/geyser/api/event/downstream/DownstreamSessionInitializeEvent.java`:

```java
package org.geysermc.geyser.api.event.downstream;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.connection.ConnectionEvent;
import org.geysermc.mcprotocollib.network.ClientSession;

import java.util.Objects;

/**
 * Called after Geyser has created the Java downstream session and registered
 * MCProtocolLib's protocol listener, but before Geyser registers its packet
 * translator and before the downstream connection is started.
 *
 * This event is intended for extensions that must install an ordered
 * downstream packet listener before any Java connection traffic is processed.
 */
public final class DownstreamSessionInitializeEvent extends ConnectionEvent {
    private final ClientSession downstreamSession;

    public DownstreamSessionInitializeEvent(
            GeyserConnection connection,
            ClientSession downstreamSession) {
        super(connection);
        this.downstreamSession = Objects.requireNonNull(downstreamSession);
    }

    /**
     * Returns the newly created MCProtocolLib client session.
     * Extensions may register session listeners here. They must not connect,
     * replace, or close the session.
     */
    public ClientSession downstreamSession() {
        return downstreamSession;
    }
}
```

This event is deliberately non-cancellable and does not allow extensions to replace the downstream connection. It exposes only the MCProtocolLib session required for ordered listener registration; it does not expose Geyser's private `GeyserSession` implementation.

### API dependency

The API module currently does not declare MCProtocolLib. Since the new public event exposes `ClientSession`, add the existing version-catalog dependency to `api/build.gradle.kts`:

```kotlin
dependencies {
    api(libs.base.api)
    api(libs.math)
    api(libs.jetbrains.annotations)
    api(libs.mcprotocollib)
}
```

This is an intentional public API dependency: consumers compiling against this event must see the same MCProtocolLib API version Geyser uses. Geyser core already declares MCProtocolLib as an API dependency, so the proposed addition makes the API module's type contract explicit. Before upstream submission, verify publication metadata and dependency alignment in the full Geyser Gradle build.

## Proposed core call site

In `core/src/main/java/org/geysermc/geyser/session/GeyserSession.java`, inside `connectDownstream()`, import the new API event and fire it before the two existing listener registrations:

```java
// Extensions must install listeners before ClientListener sends the initial handshake.
GeyserImpl.getInstance().eventBus().fireEventElseKick(
    new DownstreamSessionInitializeEvent(this, downstream), this);
if (closed) {
    return;
}

// MCPL listener comes first to handle protocol state switching before Geyser translates packets
downstream.addListener(new ClientListener(HandshakeIntent.LOGIN, Boolean.getBoolean("Geyser.ValidateDecompression")));

// Geyser adapter second to ensure translating packets in the correct states
downstream.addListener(new GeyserSessionAdapter(this));
```

The event must be fired before `ClientListener` registration. This ordering is essential: `ClientListener.connected()` sends the initial handshake and login-start packets synchronously during `ConnectedEvent`. If RealCoords were registered after it, those first outbound packets would bypass the Netty handler. The event must also remain before `GeyserSessionAdapter` and `connect(false)`. Use `fireEventElseKick` and return if the session was closed by a failing subscriber; do not silently continue with an uninitialized hook. The order is part of the hook contract and must be covered by a regression test or explicit lifecycle test.

## RealCoords listener behavior

RealCoords subscribes to `DownstreamSessionInitializeEvent` and registers a MCProtocolLib `SessionAdapter` on `event.downstreamSession()`.

In `connected(ConnectedEvent)`, the adapter runs before `ClientListener.connected()` and:

1. Obtains the active channel from the MCProtocolLib session.
2. Verifies the expected `NetworkSession` handler exists in the pipeline.
3. Inserts one duplex packet handler immediately before that manager handler.
4. Verifies the inserted handler is present exactly once.
5. Records a dedicated RealCoords diagnostic that identifies the TCP or local-session path.
6. Fails closed if any precondition or insertion step fails.

Because `LocalSession` extends `ClientNetworkSession` and its custom initializer delegates to the same `MinecraftChannelInitializer`, both connection paths are expected to share this hook. Both still require integration tests.

## Required tests before packet transformation

### Geyser-side tests / validation

- Event fires exactly once for each downstream connection attempt.
- Event observes a non-null downstream session.
- Listener order after the event is extension listener, `ClientListener`, then `GeyserSessionAdapter`.
- Event fires before `ClientListener` registration and before `connect(false)`.
- TCP and LocalSession both reach `ConnectedEvent` with the expected manager handler installed.
- Event is not fired if `SessionLoginEvent` cancels the connection.
- Reconnect / server-transfer paths fire a fresh event for the new downstream session.

### RealCoords-side tests / validation

- Handler installation is idempotent and scoped to one downstream session.
- Missing manager handler causes fail-closed behavior, not a late fallback.
- Inbound decoded packets reach RealCoords before Geyser's translator.
- Outbound packet objects reach RealCoords before MCProtocolLib serialization.
- The initial handshake and login sequence remains unchanged.
- Disconnect removes per-session state and does not retain channel references.
- No coordinate transformation is enabled until the above ordering is demonstrated in both TCP and LocalSession integration tests.

## Alternatives considered

### Polling from SessionLoginEvent

Rejected. The downstream is not assigned during the callback, and the Geyser tick EventLoop has no guaranteed ordering against MCProtocolLib's Netty EventLoop.

### SessionJoinEvent

Rejected. It occurs after Java login and initial world-state handling.

### Reflection into MCProtocolLib listener internals

Rejected. It is brittle, bypasses supported APIs, and still introduces concurrency hazards.

### Geyser-owned SPI hiding MCProtocolLib

Possible, but a useful SPI would need to expose ordered packet interception and replacement semantics. That is a larger API surface than this narrow event. Revisit only if Geyser maintainers do not want MCProtocolLib types in the published API.

## Scope and safety

This proposal only creates an initialization hook. It does not yet transform coordinates, alter world packets, change player state, or modify production behavior. RealCoords remains intentionally unwired until the hook is implemented, built, and integration-tested against the exact Geyser target.
