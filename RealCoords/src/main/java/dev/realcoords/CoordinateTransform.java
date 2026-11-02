package dev.realcoords;

/**
 * Immutable conversion between the Java/Geyser world coordinates and the
 * Bedrock client's shifted coordinate space.
 *
 * Absolute positions are translated; relative vectors and local sub-chunk
 * offsets must not be passed through this class.
 */
final class CoordinateTransform {
    private final int offsetX;
    private final int offsetZ;

    CoordinateTransform(int offsetX, int offsetZ) {
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    double toBedrockX(double realX) { return realX - offsetX; }
    double toBedrockZ(double realZ) { return realZ - offsetZ; }
    double toJavaX(double bedrockX) { return bedrockX + offsetX; }
    double toJavaZ(double bedrockZ) { return bedrockZ + offsetZ; }

    float toBedrockX(float realX) { return realX - offsetX; }
    float toBedrockZ(float realZ) { return realZ - offsetZ; }
    float toJavaX(float bedrockX) { return bedrockX + offsetX; }
    float toJavaZ(float bedrockZ) { return bedrockZ + offsetZ; }

    int toBedrockX(int realX) { return realX - offsetX; }
    int toBedrockZ(int realZ) { return realZ - offsetZ; }
    int toJavaX(int bedrockX) { return bedrockX + offsetX; }
    int toJavaZ(int bedrockZ) { return bedrockZ + offsetZ; }

    int toBedrockChunkX(int realChunkX) {
        return realChunkX - Math.floorDiv(offsetX, 16);
    }

    int toBedrockChunkZ(int realChunkZ) {
        return realChunkZ - Math.floorDiv(offsetZ, 16);
    }

}
