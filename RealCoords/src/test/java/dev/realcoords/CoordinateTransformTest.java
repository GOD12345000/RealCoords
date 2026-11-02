package dev.realcoords;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoordinateTransformTest {
    @Test
    void convertsAbsoluteCoordinatesInBothDirections() {
        CoordinateTransform transform = new CoordinateTransform(1024, -512);

        assertEquals(976.5, transform.toBedrockX(2000.5), 0.0001);
        assertEquals(536.75, transform.toBedrockZ(24.75), 0.0001);
        assertEquals(2000.5, transform.toJavaX(976.5), 0.0001);
        assertEquals(24.75, transform.toJavaZ(536.75), 0.0001);
    }

    @Test
    void preservesNegativeCoordinates() {
        CoordinateTransform transform = new CoordinateTransform(-1024, 512);

        assertEquals(-76, transform.toBedrockX(-1100));
        assertEquals(-612, transform.toBedrockZ(-100));
        assertEquals(-1100, transform.toJavaX(-76));
        assertEquals(-100, transform.toJavaZ(-612));
    }

    @Test
    void convertsChunkCoordinatesUsingFloorDivision() {
        CoordinateTransform transform = new CoordinateTransform(-32, 48);

        assertEquals(7, transform.toBedrockChunkX(5));
        assertEquals(-5, transform.toBedrockChunkZ(-2));
    }

    @Test
    void roundTripsIntegerCoordinates() {
        CoordinateTransform transform = new CoordinateTransform(256, 768);

        assertEquals(-17, transform.toJavaX(transform.toBedrockX(-17)));
        assertEquals(4097, transform.toJavaZ(transform.toBedrockZ(4097)));
    }
}
