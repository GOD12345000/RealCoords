package dev.realcoords;

/** Packet-derived state owned by one Java packet handler and its Netty event loop. */
final class TransitionDeferralState {
    private int selfEntityId = Integer.MIN_VALUE;
    private int ridingVehicleId = Integer.MIN_VALUE;
    private boolean containerOpen;
    private boolean sleeping;
    private boolean gliding;\n    private boolean observationUnavailable;

    void initialize(int entityId) {
        selfEntityId = entityId;
        clearTransientState();
    }

    void resetForRespawn() { clearTransientState(); }
    void observeContainerOpened() { containerOpen = true; }
    void observeContainerClosed() { containerOpen = false; }

    void observePassengers(int vehicleEntityId, int... passengerEntityIds) {
        boolean containsSelf = false;
        if (passengerEntityIds != null) {
            for (int passenger : passengerEntityIds) {
                if (passenger == selfEntityId) { containsSelf = true; break; }
            }
        }
        if (containsSelf) ridingVehicleId = vehicleEntityId;
        else if (ridingVehicleId == vehicleEntityId) ridingVehicleId = Integer.MIN_VALUE;
    }

    void observePlayerMetadata(int entityId, int metadataId, Object value) {
        if (entityId != selfEntityId || value == null) return;
        if (metadataId == 6) {
            sleeping = value.toString().equalsIgnoreCase("SLEEPING");
        } else if (metadataId == 0 && value instanceof Number flags) {
            gliding = (flags.byteValue() & 0x80) != 0;
        }
    }

    void markObservationUnavailable() { observationUnavailable = true; }

    boolean shouldDefer() { return observationUnavailable || containerOpen || isRiding() || sleeping || gliding; }
    int selfEntityId() { return selfEntityId; }
    boolean isContainerOpen() { return containerOpen; }
    boolean isRiding() { return ridingVehicleId != Integer.MIN_VALUE; }
    boolean isSleeping() { return sleeping; }
    boolean isGliding() { return gliding; }

    private void clearTransientState() {
        ridingVehicleId = Integer.MIN_VALUE;
        containerOpen = false;
        sleeping = false;
        gliding = false;
    }
}
