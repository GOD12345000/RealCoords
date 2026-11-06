package dev.realcoords;

/**
 * Per-player origin decision state for Java-layer packet rewriting.
 *
 * This class contains only the phase-1 decision logic. It does not mutate packets;
 * callers must apply the returned offsets atomically before forwarding the position
 * packet and all subsequent world packets.
 */
final class JavaOriginState {
    record Offsets(int x, int z) {
    }

    record Snapshot(int offsetX, int offsetZ, boolean changed, boolean initialized) {
    }

    private final ShiftConfig config;
    // The Netty handler is channel-confined; publish the immutable pair for the Bedrock event loop.
    private final java.util.Map<Integer, Offsets> dimensionOffsets = new java.util.HashMap<>();
    private int dimension;
    private int offsetX;
    private int offsetZ;
    private volatile Offsets publishedOffsets = new Offsets(0, 0);
    private double lastX;
    private double lastZ;
    private boolean hasPosition;
    private boolean resetPending = true;

    JavaOriginState(ShiftConfig config) {
        this.config = config;
    }

    void login() {
        dimensionOffsets.clear();
        dimension = 0;
        offsetX = 0;
        offsetZ = 0;
        hasPosition = false;
        resetPending = true;
        publishOffsets();
    }

    void respawn(int nextDimension) {
        dimensionOffsets.put(dimension, new Offsets(offsetX, offsetZ));
        Offsets restored = dimensionOffsets.getOrDefault(nextDimension, new Offsets(0, 0));
        dimension = nextDimension;
        offsetX = restored.x();
        offsetZ = restored.z();
        publishOffsets();
        hasPosition = false;
        resetPending = true;
    }

    /**
     * Observe an absolute server position. A new origin is selected on the first
     * position after login/respawn, or after a teleport farther than the view
     * distance. Ordinary movement never changes the origin in phase 1.
     */
    Snapshot absolutePosition(double x, double z, int viewDistanceChunks) {
        return absolutePosition(x, z, viewDistanceChunks, true, true);
    }

    Snapshot absolutePosition(double x, double z, int viewDistanceChunks,
                              boolean absoluteX, boolean absoluteZ) {
        int oldX = offsetX;
        int oldZ = offsetZ;
        double nextX = absoluteX ? x : (hasPosition ? lastX + x : x);
        double nextZ = absoluteZ ? z : (hasPosition ? lastZ + z : z);

        if (resetPending || !hasPosition) {
            if (absoluteX) offsetX = candidateOffset(nextX, offsetX);
            if (absoluteZ) offsetZ = candidateOffset(nextZ, offsetZ);
            if (absoluteX && absoluteZ) resetPending = false;
        } else if (absoluteX && absoluteZ) {
            double viewDistance = Math.max(0, viewDistanceChunks) * 16.0;
            if (Math.hypot(nextX - lastX, nextZ - lastZ) > viewDistance) {
                offsetX = nextOffset(offsetX, nextX);
                offsetZ = nextOffset(offsetZ, nextZ);
            }
        }

        lastX = nextX;
        lastZ = nextZ;
        hasPosition = true;
        publishOffsets();
        return new Snapshot(offsetX, offsetZ, oldX != offsetX || oldZ != offsetZ, true);
    }

    boolean acceptServerboundPosition(double x, double z, double maxJump) {
        if (hasPosition && Math.hypot(x - lastX, z - lastZ) > maxJump) {
            return false;
        }
        lastX = x;
        lastZ = z;
        hasPosition = true;
        return true;
    }

    Offsets offsets() {
        return publishedOffsets;
    }

    int offsetX() {
        return publishedOffsets.x();
    }

    int offsetZ() {
        return publishedOffsets.z();
    }

    private void publishOffsets() {
        publishedOffsets = new Offsets(offsetX, offsetZ);
    }

    private int candidateOffset(double real, int current) {
        ShiftConfig.Dim dim = config.dim(dimension);
        if (!dim.enabled) {
            return 0;
        }
        if (current != 0 && Math.abs(real) < dim.reset) {
            return 0;
        }
        if (Math.abs(real - current) > dim.trigger) {
            return aligned(real, dim.alignment);
        }
        return current;
    }

    private int nextOffset(int current, double real) {
        ShiftConfig.Dim dim = config.dim(dimension);
        if (!dim.enabled) {
            return 0;
        }
        if (current != 0 && Math.abs(real) < dim.reset) {
            return 0;
        }
        if (Math.abs(real - current) > dim.trigger) {
            return aligned(real, dim.alignment);
        }
        return current;
    }

    private static int aligned(double coordinate, int alignment) {
        int safeAlignment = Math.max(16, (alignment / 16) * 16);
        return (int) (Math.round(coordinate / safeAlignment) * safeAlignment);
    }
}
