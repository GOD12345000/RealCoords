package dev.realcoords;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateTransformTest {
    @Test
    void localizesLargeCoordinatesBeforeFloatNarrowing() {
        CoordinateTransform transform = CoordinateTransform.alignedTo(25_000_000.001, -17_000_000.125, 16);

        double localX = transform.toLocalX(25_000_000.001);
        double localZ = transform.toLocalZ(-17_000_000.125);

        assertEquals(25_000_000.001 - transform.originX(), localX, 1e-9);
        assertEquals(-17_000_000.125 - transform.originZ(), localZ, 1e-9);
        assertTrue(Math.abs(localX) < 16);
        assertTrue(Math.abs(localZ) < 16);
        assertEquals((float) localX, (float) transform.toLocalX(25_000_000.001));
    }

    @Test
    void doubleRoundTripUsesSameImmutableOrigin() {
        CoordinateTransform transform = new CoordinateTransform(-23_255_296, 17_909_760);
        double x = -23_255_206.000_123;
        double z = 17_909_758.000_456;

        assertEquals(x, transform.toRealX(transform.toLocalX(x)), 1e-9);
        assertEquals(z, transform.toRealZ(transform.toLocalZ(z)), 1e-9);
    }

    @Test
    void blockAndChunkCoordinatesRoundTripExactly() {
        CoordinateTransform transform = new CoordinateTransform(-23_255_296, 17_909_760);

        int localBlockX = transform.toLocalBlockX(-23_255_206);
        int localBlockZ = transform.toLocalBlockZ(17_909_758);
        assertEquals(-23_255_206, transform.toRealBlockX(localBlockX));
        assertEquals(17_909_758, transform.toRealBlockZ(localBlockZ));

        int localChunkX = transform.toLocalChunkX(-23_255_206 >> 4);
        int localChunkZ = transform.toLocalChunkZ(17_909_758 >> 4);
        assertEquals(-23_255_206 >> 4, transform.toRealChunkX(localChunkX));
        assertEquals(17_909_758 >> 4, transform.toRealChunkZ(localChunkZ));
    }

    @Test
    void rejectsNonAlignedOriginsAndInvalidAlignment() {
        assertThrows(IllegalArgumentException.class, () -> new CoordinateTransform(1, 0));
        assertThrows(IllegalArgumentException.class, () -> CoordinateTransform.alignedTo(100, 100, 24));
        assertThrows(IllegalArgumentException.class, () -> CoordinateTransform.alignedTo(Double.NaN, 0, 16));
    }

    @Test
    void integerOverflowIsNotSilentlyWrapped() {
        CoordinateTransform transform = new CoordinateTransform(2_147_483_632, 0);
        assertThrows(ArithmeticException.class, () -> transform.toLocalBlockX(Integer.MIN_VALUE));
    }
}
