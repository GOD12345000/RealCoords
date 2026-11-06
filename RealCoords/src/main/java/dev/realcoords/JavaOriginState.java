package dev.realcoords;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-player origin decision state for Java-layer packet rewriting.
 *
 * This class owns per-dimension origin decisions for initial login/respawn,
 * server teleports, and the opt-in live movement path. It does not mutate packets;
 * callers must reset/replay the Geyser world state before forwarding movement
 * under a newly selected live origin.
 */
final class JavaOriginState {
    record Offsets(int x, int z) {
    }

    record Snapshot(int offsetX, int offsetZ, boolean changed, boolean initialized) {
    }

    private final ShiftConfig config;
    // The Netty handler is channel-confined; publish the immutable pair for the Bedrock event loop.
    private final Map<Integer, Offsets> dimensionOffsets = new HashMap<>();
    private int dimension;
    private int offsetX;
    private int offsetZ;
    private volatile Offsets publishedOffsets = new Offsets(0, 0);
    private double lastX;
    private double lastZ;
    private boolean hasPosition;
    private boolean resetPending = true;
    private boolean needsAbsoluteX = true;
    private boolean needsAbsoluteZ = true;
    private long lastMovementOriginShiftMs;
    private Snapshot pendingMovementOriginChange;

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
        needsAbsoluteX = true;
        needsAbsoluteZ = true;
        lastMovementOriginShiftMs = 0;
        pendingMovementOriginChange = null;
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
        needsAbsoluteX = true;
        needsAbsoluteZ = true;
        lastMovementOriginShiftMs = 0;
        pendingMovementOriginChange = null;
    }

    /**
     * Observe server position components. A new origin is selected on the first
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
        double nextX = absoluteX ? x : (needsAbsoluteX ? lastX : lastX + x);
        double nextZ = absoluteZ ? z : (needsAbsoluteZ ? lastZ : lastZ + z);

        if (resetPending || !hasPosition) {
            if (needsAbsoluteX && absoluteX) {
                offsetX = candidateOffset(nextX, offsetX);
                needsAbsoluteX = false;
            }
            if (needsAbsoluteZ && absoluteZ) {
                offsetZ = candidateOffset(nextZ, offsetZ);
                needsAbsoluteZ = false;
            }
            resetPending = needsAbsoluteX || needsAbsoluteZ;
        } else if (absoluteX && absoluteZ) {
            double viewDistance = Math.max(0, viewDistanceChunks) * 16.0;
            if (Math.hypot(nextX - lastX, nextZ - lastZ) > viewDistance) {
                offsetX = nextOffset(offsetX, nextX);
                offsetZ = nextOffset(offsetZ, nextZ);
            }
        }

        if (absoluteX || !needsAbsoluteX) lastX = nextX;
        if (absoluteZ || !needsAbsoluteZ) lastZ = nextZ;
        hasPosition = !needsAbsoluteX && !needsAbsoluteZ;
        publishOffsets();
        dimensionOffsets.put(dimension, new Offsets(offsetX, offsetZ));
        return new Snapshot(offsetX, offsetZ, oldX != offsetX || oldZ != offsetZ, true);
    }

    boolean acceptServerboundPosition(double x, double z, double maxJump) {
        return acceptServerboundPosition(x, z, maxJump, true);
    }

    boolean observeServerboundPosition(double x, double z, double maxJump) {
        if (hasPosition && Math.hypot(x - lastX, z - lastZ) > maxJump) return false;
        lastX = x;
        lastZ = z;
        hasPosition = true;
        needsAbsoluteX = false;
        needsAbsoluteZ = false;
        resetPending = false;
        return true;
    }

    boolean acceptServerboundPosition(double x, double z, double maxJump, boolean replayCacheAvailable) {
        if (!observeServerboundPosition(x, z, maxJump)) return false;

        if (config.shiftWhileMoving && replayCacheAvailable) {
            long now = System.currentTimeMillis();
            if (now - lastMovementOriginShiftMs >= Math.max(0, config.cooldownMs)) {
                int oldX = offsetX;
                int oldZ = offsetZ;
                offsetX = nextOffset(offsetX, x);
                offsetZ = nextOffset(offsetZ, z);
                if (oldX != offsetX || oldZ != offsetZ) {
                    publishOffsets();
                    dimensionOffsets.put(dimension, new Offsets(offsetX, offsetZ));
                    pendingMovementOriginChange = new Snapshot(offsetX, offsetZ, true, true);
                    lastMovementOriginShiftMs = now;
                }
            }
        }
        return true;
    }

    Snapshot takeMovementOriginChange() {
        Snapshot snapshot = pendingMovementOriginChange;
        pendingMovementOriginChange = null;
        return snapshot;
    }

    double realX() {
        return lastX;
    }

    double realZ() {
        return lastZ;
    }

    boolean hasPosition() {
        return hasPosition;
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
