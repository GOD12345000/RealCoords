package dev.realcoords;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitionWalkFallbackTest {
    @Test
    void noneKeepsDeferringEvenAfterEightTimesTriggerDistance() {
        assertEquals(TransitionWalkFallback.Action.DEFER,
                TransitionWalkFallback.decide("none", 400_001, 0, 50_000));
    }

    @Test
    void kickFallbackActivatesOnlyAfterEightTimesTriggerDistance() {
        assertEquals(TransitionWalkFallback.Action.DEFER,
                TransitionWalkFallback.decide("kick", -400_000, 0, 50_000));
        assertEquals(TransitionWalkFallback.Action.KICK,
                TransitionWalkFallback.decide("kick", -400_001, 0, 50_000));
    }

    @Test
    void reoriginFallbackActivatesOnlyAfterEightTimesTriggerDistance() {
        assertEquals(TransitionWalkFallback.Action.DEFER,
                TransitionWalkFallback.decide("reorigin", 0, 400_000, 50_000));
        assertEquals(TransitionWalkFallback.Action.REORIGIN,
                TransitionWalkFallback.decide("reorigin", 0, 400_001, 50_000));
    }

    @Test
    void thresholdUsesTheFurthestLocalAxisAndRejectsInvalidTrigger() {
        assertEquals(TransitionWalkFallback.Action.KICK,
                TransitionWalkFallback.decide("kick", 1, -400_001, 50_000));
        assertEquals(TransitionWalkFallback.Action.DEFER,
                TransitionWalkFallback.decide("kick", 900_000, 0, 0));
    }
}
