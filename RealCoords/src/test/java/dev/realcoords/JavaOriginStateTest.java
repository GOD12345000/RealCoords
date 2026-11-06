package dev.realcoords;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaOriginStateTest {
    private ShiftConfig config;
    private JavaOriginState state;

    @BeforeEach
    void setUp() {
        config = new ShiftConfig();
        ShiftConfig.Dim overworld = new ShiftConfig.Dim();
        overworld.trigger = 50_000;
        overworld.reset = 20_000;
        overworld.alignment = 256;
        config.dims.put(0, overworld);
        state = new JavaOriginState(config);
    }

    @Test
    void firstAbsolutePositionAtFarCoordinatesSelectsAlignedOrigin() {
        JavaOriginState.Snapshot result = state.absolutePosition(-23_000_000, 17_000_000, 10);

        assertTrue(result.changed());
        assertEquals(Math.round(-23_000_000.0 / 256) * 256, result.offsetX());
        assertEquals(Math.round(17_000_000.0 / 256) * 256, result.offsetZ());
    }

    @Test
    void ordinaryMovementDoesNotChangeOrigin() {
        JavaOriginState.Snapshot first = state.absolutePosition(100_000, 100_000, 10);
        JavaOriginState.Snapshot next = state.absolutePosition(100_100, 100_100, 10);

        assertTrue(first.changed());
        assertFalse(next.changed());
        assertEquals(first.offsetX(), next.offsetX());
        assertEquals(first.offsetZ(), next.offsetZ());
    }

    @Test
    void boundaryJitterWithinViewDistanceDoesNotRepeatedlyShiftOrigin() {
        JavaOriginState.Snapshot first = state.absolutePosition(49_999, 49_999, 10);
        JavaOriginState.Snapshot overThreshold = state.absolutePosition(50_001, 50_001, 10);
        JavaOriginState.Snapshot back = state.absolutePosition(49_998, 49_998, 10);

        assertFalse(first.changed());
        assertFalse(overThreshold.changed());
        assertFalse(back.changed());
        assertEquals(0, back.offsetX());
        assertEquals(0, back.offsetZ());
    }

    @Test
    void farTeleportChangesOriginBeforePositionIsForwarded() {
        state.absolutePosition(100_000, 100_000, 10);

        JavaOriginState.Snapshot result = state.absolutePosition(23_000_000, -17_000_000, 10);

        assertTrue(result.changed());
        assertEquals(Math.round(23_000_000.0 / 256) * 256, result.offsetX());
        assertEquals(Math.round(-17_000_000.0 / 256) * 256, result.offsetZ());
    }

    @Test
    void distantTeleportBackInsideResetRadiusClearsOrigin() {
        state.absolutePosition(23_000_000, -17_000_000, 10);

        JavaOriginState.Snapshot result = state.absolutePosition(1_000, -1_000, 10);

        assertEquals(0, result.offsetX());
        assertEquals(0, result.offsetZ());
        assertTrue(result.changed());
    }

    @Test
    void respawnReevaluatesOriginOnNextAbsolutePosition() {
        state.absolutePosition(100_000, 100_000, 10);
        state.respawn(0);

        JavaOriginState.Snapshot result = state.absolutePosition(-23_000_000, 17_000_000, 10);

        assertTrue(result.changed());
        assertEquals(Math.round(-23_000_000.0 / 256) * 256, result.offsetX());
        assertEquals(Math.round(17_000_000.0 / 256) * 256, result.offsetZ());
    }

    @Test
    void initialMixedRelativePositionWaitsForBothAbsoluteAxes() {
        JavaOriginState.Snapshot partial =
                state.absolutePosition(23_000_000, 12.5, 10, true, false);
        assertNotEquals(0, partial.offsetX());
        assertEquals(0, partial.offsetZ());

        JavaOriginState.Snapshot complete =
                state.absolutePosition(23_000_010, 17_000_000, 10, true, true);
        assertNotEquals(0, complete.offsetX());
        assertNotEquals(0, complete.offsetZ());
        assertTrue(Math.abs(23_000_010 - complete.offsetX()) < 256);
        assertTrue(Math.abs(17_000_000 - complete.offsetZ()) < 256);
    }

    @Test
    void initialAbsoluteAxesCanArriveInSeparatePackets() {
        JavaOriginState.Snapshot xOnly =
                state.absolutePosition(23_000_000, 12.5, 10, true, false);
        assertNotEquals(0, xOnly.offsetX());
        assertEquals(0, xOnly.offsetZ());

        JavaOriginState.Snapshot zOnly =
                state.absolutePosition(2.5, 17_000_000, 10, false, true);
        assertNotEquals(0, zOnly.offsetX());
        assertNotEquals(0, zOnly.offsetZ());
        assertTrue(Math.abs(23_000_002.5 - zOnly.offsetX()) < 256);
        assertTrue(Math.abs(17_000_000 - zOnly.offsetZ()) < 256);
    }

    @Test
    void eachDimensionRestoresItsOwnOrigin() {
        state.absolutePosition(100_000, 100_000, 10);
        int overworldOffset = state.offsetX();

        state.respawn(1);
        JavaOriginState.Snapshot nether = state.absolutePosition(1_000_000, 1_000_000, 10);
        assertNotEquals(overworldOffset, nether.offsetX());

        state.respawn(0);
        JavaOriginState.Snapshot overworld = state.absolutePosition(100_100, 100_100, 10);
        assertEquals(overworldOffset, overworld.offsetX());
        assertEquals(overworldOffset, overworld.offsetZ());
    }

    @Test
    void eachDimensionRetainsItsOwnOriginOffset() {
        JavaOriginState.Snapshot overworld = state.absolutePosition(23_000_000, -17_000_000, 10);

        state.respawn(1);
        assertEquals(0, state.offsetX());
        assertEquals(0, state.offsetZ());
        JavaOriginState.Snapshot nether = state.absolutePosition(7_000_000, 8_000_000, 10);

        state.respawn(0);
        assertEquals(overworld.offsetX(), state.offsetX());
        assertEquals(overworld.offsetZ(), state.offsetZ());

        state.respawn(1);
        assertEquals(nether.offsetX(), state.offsetX());
        assertEquals(nether.offsetZ(), state.offsetZ());
    }

    @Test
    void alignmentIsAlwaysAChunkMultiple() {
        JavaOriginState.Snapshot result = state.absolutePosition(23_000_001, -17_000_003, 10);

        assertEquals(0, Math.floorMod(result.offsetX(), 16));
        assertEquals(0, Math.floorMod(result.offsetZ(), 16));
    }
}
