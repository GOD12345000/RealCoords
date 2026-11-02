package dev.realcoords;

/**
 * Immutable horizontal coordinate transform for one origin snapshot.
 *
 * <p>All arithmetic stays in double precision until a caller deliberately
 * converts a localized value to a narrower wire representation. Relative
 * movement vectors are not coordinates and must never be passed here.</p>
 */
public record CoordinateTransform(int originX, int originZ) {
    public static final int DEFAULT_ALIGNMENT = 16;

    public CoordinateTransform {
        if (Math.floorMod(originX, DEFAULT_ALIGNMENT) != 0
                || Math.floorMod(originZ, DEFAULT_ALIGNMENT) != 0) {
            throw new IllegalArgumentException("Origin must be aligned to 16 blocks");
        }
    }

    /** Build a chunk-aligned origin near the supplied absolute Java position. */
    public static CoordinateTransform alignedTo(double realX, double realZ, int alignment) {
        validateAlignment(alignment);
        return new CoordinateTransform(
                alignedOrigin(realX, alignment),
                alignedOrigin(realZ, alignment)
        );
    }

    public double toLocalX(double realX) {
        return realX - (double) originX;
    }

    public double toLocalZ(double realZ) {
        return realZ - (double) originZ;
    }

    public double toRealX(double localX) {
        return localX + (double) originX;
    }

    public double toRealZ(double localZ) {
        return localZ + (double) originZ;
    }

    public int toLocalBlockX(int realX) {
        return Math.subtractExact(realX, originX);
    }

    public int toLocalBlockZ(int realZ) {
        return Math.subtractExact(realZ, originZ);
    }

    public int toRealBlockX(int localX) {
        return Math.addExact(localX, originX);
    }

    public int toRealBlockZ(int localZ) {
        return Math.addExact(localZ, originZ);
    }

    public int toLocalChunkX(int realChunkX) {
        return Math.subtractExact(realChunkX, originX / 16);
    }

    public int toLocalChunkZ(int realChunkZ) {
        return Math.subtractExact(realChunkZ, originZ / 16);
    }

    public int toRealChunkX(int localChunkX) {
        return Math.addExact(localChunkX, originX / 16);
    }

    public int toRealChunkZ(int localChunkZ) {
        return Math.addExact(localChunkZ, originZ / 16);
    }

    private static int alignedOrigin(double coordinate, int alignment) {
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException("Coordinate must be finite");
        }
        double units = Math.rint(coordinate / alignment);
        double aligned = units * alignment;
        if (aligned < Integer.MIN_VALUE || aligned > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Aligned origin is outside integer block-coordinate range");
        }
        return (int) aligned;
    }

    private static void validateAlignment(int alignment) {
        if (alignment < DEFAULT_ALIGNMENT || alignment % DEFAULT_ALIGNMENT != 0) {
            throw new IllegalArgumentException("Alignment must be a positive multiple of 16");
        }
    }
}
