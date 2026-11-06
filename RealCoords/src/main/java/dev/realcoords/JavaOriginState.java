package dev.realcoords;

/**
 * Per-player origin decision state for Java-layer packet rewriting.
 *
 * This class contains only the phase-1 decision logic. It does not mutate packets;
 * callers must apply the returned offsets atomically before forwarding the position
 * packet and all subsequent world packets.
 */
final class JavaOriginState {
    record Snapshot(int offsetX, int offsetZ, boolean changed, boolean initialized) {
    }

    private final ShiftConfig config;
    private int dimension;
    private int offsetX;
    private int offsetZ;
    private double lastX;
    private double lastZ;
    private boolean hasPosition;
    private boolean resetPending = true;

    JavaOriginState(ShiftConfig config) {
        this.config = config;
    }

    void login() {
        offsetX = 0;
        offsetZ = 0;
        hasPosition = false;
        resetPending = true;
    }

    void respawn(int nextDimension) {
        dimension = nextDimension;
        hasPosition = false;
        resetPending = true;
    }

    /**
     * Observe an absolute server position. A new origin is selected on the first
     * position after login/respawn, or after a teleport farther than the view
     * distance. Ordinary movement never changes the origin in phase 1.
     */
    Snapshot absolutePosition(double x, double z, int viewDistanceChunks) {
        int oldX = offsetX;
        int oldZ = offsetZ;

        if (resetPending || !hasPosition) {
            offsetX = candidateOffset(x, offsetX);
            offsetZ = candidateOffset(z, offsetZ);
            resetPending = false;
        } else {
            double viewDistance = Math.max(0, viewDistanceChunks) * 16.0;
            if (Math.hypot(x - lastX, z - lastZ) > viewDistance) {
                offsetX = nextOffset(offsetX, x);
                offsetZ = nextOffset(offsetZ, z);
            }
        }

        lastX = x;
        lastZ = z;
        hasPosition = true;
        return new Snapshot(offsetX, offsetZ, oldX != offsetX || oldZ != offsetZ, true);
    }

    int offsetX() {
        return offsetX;
    }

    int offsetZ() {
        return offsetZ;
    }

    private int candidateOffset(double real, int current) {
        ShiftConfig.Dim dim = config.dim(dimension);
        if (!dim.enabled || Math.abs(real) <= dim.trigger) {
            return 0;
        }
        return aligned(real, dim.alignment);
    }

    private int nextOffset(int current, double real) {
        ShiftConfig.Dim dim = config.dim(dimension);
        if (!dim.enabled) {
            return 0;
        }
        if (Math.abs(real - current) > dim.trigger) {
            return aligned(real, dim.alignment);
        }
        if (current != 0 && Math.abs(real) < dim.reset) {
            return 0;
        }
        return current;
    }

    private static int aligned(double coordinate, int alignment) {
        int safeAlignment = Math.max(16, (alignment / 16) * 16);
        return (int) (Math.round(coordinate / safeAlignment) * safeAlignment);
    }
}
