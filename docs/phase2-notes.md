# Phase 2 B — transition deferral packet notes

## Ownership and threading

The deferral state is owned by each `JavaPacketTransformHandler` instance. Its only writer and reader is the handler on the **MCProtocolLib downstream Java channel's Netty event loop**:

- Inbound decoded Java server packets enter `channelRead(ChannelHandlerContext, Object)`.
- `observeDeferralPacket` is called synchronously before packet transformation and before a packet can be buffered or passed downstream.
- The event-loop invariant is checked against `channelContext.executor().inEventLoop()`.
- Outbound movement reads `deferralState.shouldDefer()` in `write(...)`, which is invoked on that same channel event loop.

No `GeyserSession` state is read to decide whether a transition is safe. The Geyser tick event loop is used elsewhere for Geyser-owned metadata updates, but it is not the owner of this deferral state and must not read or mutate it.

## State source map (MCProtocolLib 1.21.8 packet family)

| State | Source packet | Field / interpretation | Update |
|---|---|---|---|
| Local player entity ID | `ClientboundLoginPacket` | `getEntityId()` | Initialize state and clear transient deferrals |
| Container open | `ClientboundOpenScreenPacket` | Packet arrival | Set open |
| Container closed | `ClientboundContainerClosePacket` | Packet arrival | Clear open |
| Riding | `ClientboundSetPassengersPacket` | `getEntityId()`, `getPassengerIds()` | Defer only when passenger list contains local player; clear only on matching vehicle update without local player |
| Sleeping | `ClientboundSetEntityDataPacket` | Local entity only; metadata id 6, value `Pose.SLEEPING` | Set/clear from current pose |
| Elytra gliding | `ClientboundSetEntityDataPacket` | Local entity only; metadata id 0, Byte flag bit `0x80` | Set/clear from entity flags |
| Dimension/respawn reset | `ClientboundRespawnPacket` | Packet arrival | Clear container, riding, sleeping and gliding state |

Entity metadata for any entity other than the login entity ID is ignored. Login establishes the local entity ID; respawn preserves that ID but resets transient state. In this protocol, a dimension change is delivered through the respawn packet path, so the same reset is applied.

## Fail-safe behavior

Before Login initializes the local entity ID, packet-derived state is unknown and `shouldDefer()` returns true. If a required metadata observation is malformed or unavailable, the state is marked unknown and `shouldDefer()` remains true. This is intentionally conservative: transition eligibility is not inferred from missing data. A new Login or Respawn lifecycle reset clears transient/unknown state.

The handler uses direct typed MCProtocolLib getters; no reflective field or method lookup is used for deferral observations.
