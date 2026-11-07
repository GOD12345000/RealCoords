package dev.realcoords;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitionTimingPolicyTest {
    @Test
    void cooldownAndGraceStartOnlyAfterTransitionCompletes() {
        TransitionTimingPolicy policy = new TransitionTimingPolicy(600, 20);

        assertFalse(policy.cooldownActive(1_000_000_000L));
        assertFalse(policy.graceActive(1_000_000_000L));

        policy.transitionCompleted(1_000_000_000L);

        assertTrue(policy.cooldownActive(1_000_000_001L));
        assertTrue(policy.graceActive(1_000_000_001L));
        assertTrue(policy.safetyGuardRelaxed(false, 1_000_000_001L));
    }

    @Test
    void cooldownAndGraceExpireAtTheirConfiguredTickBoundaries() {
        TransitionTimingPolicy policy = new TransitionTimingPolicy(600, 20);
        policy.transitionCompleted(5_000_000_000L);

        assertTrue(policy.cooldownActive(34_999_999_999L));
        assertFalse(policy.cooldownActive(35_000_000_000L));
        assertTrue(policy.graceActive(5_999_999_999L));
        assertFalse(policy.graceActive(6_000_000_000L));
        assertFalse(policy.safetyGuardRelaxed(false, 35_000_000_000L));
    }

    @Test
    void zeroCooldownAndGraceExpireImmediately() {
        TransitionTimingPolicy policy = new TransitionTimingPolicy(0, 0);
        policy.transitionCompleted(9_000_000_000L);

        assertFalse(policy.cooldownActive(9_000_000_000L));
        assertFalse(policy.graceActive(9_000_000_000L));
        assertFalse(policy.safetyGuardRelaxed(false, 9_000_000_000L));
    }

    @Test
    void activeTransitionAlwaysRelaxesMovementGuard() {
        TransitionTimingPolicy policy = new TransitionTimingPolicy(600, 20);

        assertTrue(policy.safetyGuardRelaxed(true, 1L));
    }
    @Test
    void movementJumpLimitUsesBoundedGraceLimitOnlyDuringTransitionAndGrace() {
        TransitionTimingPolicy policy = new TransitionTimingPolicy(600, 20);

        assertEquals(64.0, policy.movementJumpLimit(false, 1_000_000_000L, 64.0, 256.0));
        assertEquals(256.0, policy.movementJumpLimit(true, 1_000_000_000L, 64.0, 256.0));

        policy.transitionCompleted(2_000_000_000L);
        assertEquals(256.0, policy.movementJumpLimit(false, 2_000_000_001L, 64.0, 256.0));
        assertEquals(64.0, policy.movementJumpLimit(false, 3_000_000_000L, 64.0, 256.0));
    }

}
