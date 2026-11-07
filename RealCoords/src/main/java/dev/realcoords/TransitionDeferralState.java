package dev.realcoords;

/** Per-downstream-channel deferral state; mutated only on the Netty handler event loop. */
final class TransitionDeferralState {
    private int selfEntityId = Integer.MIN_VALUE;
    void initialize(int entityId) { selfEntityId = entityId; }
    void resetForRespawn() { }
    void observeContainerOpened() { }
    void observeContainerClosed() { }
    void observePassengers(int vehicleEntityId, int... passengerEntityIds) { }
    void observePlayerMetadata(int entityId, int metadataId, Object value) { }
    boolean shouldDefer() { return false; }
    int selfEntityId() { return selfEntityId; }
    boolean isContainerOpen() { return false; }
    boolean isRiding() { return false; }
    boolean isSleeping() { return false; }
    boolean isGliding() { return false; }
}
