# RealCoords Startup Agent

Status: initial implementation committed; runtime validation is still required.

## Why an agent is used

The official Geyser 2.12.1 extension API does not expose the Java downstream MCProtocolLib session at the required point in its lifecycle. `SessionLoginEvent` occurs before the downstream session is constructed, while `SessionJoinEvent` occurs after initial login/world packets. Polling cannot guarantee ordering against MCProtocolLib's Netty event loop.

The startup agent leaves the official Geyser JAR untouched. It instruments the exact Geyser 2.12.1 method:

`org.geysermc.geyser.session.GeyserSession.connectDownstream()V`

Immediately before the first of the two downstream `addListener(SessionListener)` calls, it invokes the system-classloader bridge with the Geyser session and downstream session. The extension registers a RealCoords `SessionAdapter` at that point. Geyser then registers its original `ClientListener` and `GeyserSessionAdapter` in the original order.

This is a runtime bytecode hook, not a public Geyser API. It is deliberately narrow and fails closed if the target method or expected listener-call count changes.

## Build

Requires JDK 21 and Gradle 8.10.2:

```bash
gradle --no-daemon -p RealCoordsAgent clean build
gradle --no-daemon -p RealCoords clean build
```

Artifacts:

- `RealCoordsAgent/build/libs/RealCoordsAgent-1.0.0-all.jar`
- `RealCoords/build/libs/RealCoords-1.0.0.jar`

The extension compiles against the published, unmodified Geyser API. It does not compile against a patched API.

## Deployment

1. Stop Velocity.
2. Add the startup agent to the JVM arguments used to launch Velocity:

```bash
-javaagent:/absolute/path/RealCoordsAgent-1.0.0-all.jar
```

3. Install the RealCoords extension JAR in the Geyser extensions directory.
4. Keep the official Geyser 2.12.1 JAR unchanged.
5. Start Velocity and inspect the startup log for `[RealCoordsAgent]` messages, then the RealCoords dedicated log for callback registration and downstream listener registration.

Do not install the old Geyser patch or use a patched Geyser API/JAR with this build.

## Important limitations

- The transformer is pinned to the inspected Geyser 2.12.1 source shape. A Geyser upgrade may require a new transformer target and regression checks.
- The Java Agent must be present at JVM startup. Installing it after Velocity has started cannot work.
- The initial bridge is diagnostic only. It does not currently translate coordinates.
- A successful build does not prove the runtime transformer, extension class-loader visibility, TCP path, or LocalSession path. Those require an actual Velocity + Geyser runtime test.
- Do not enable coordinate transformations or deploy this as a production coordinate-rebasing solution yet.
