package dev.realcoords;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitionDeferralStateTest {
    @Test
    void startsClearAndInitializesOnlyThePlayersOwnEntityId() {
        TransitionDeferralState state = new TransitionDeferralState();
        assertFalse(state.shouldDefer());
        state.initialize(42);
        assertEquals(42, state.selfEntityId());
        assertFalse(state.shouldDefer());
    }

    @Test
    void containerOpenAndCloseControlDeferral() {
        TransitionDeferralState state = new TransitionDeferralState();
        state.initialize(42);
        state.observeContainerOpened();
        assertTrue(state.isContainerOpen());
        assertTrue(state.shouldDefer());
        state.observeContainerClosed();
        assertFalse(state.isContainerOpen());
        assertFalse(state.shouldDefer());
    }

    @Test
    void onlyPassengerRelationshipsContainingTheLocalPlayerCauseRidingDeferral() {
        TransitionDeferralState state = new TransitionDeferralState();
        state.initialize(42);
        state.observePassengers(100, 7, 8);
        assertFalse(state.isRiding());
        state.observePassengers(101, 42, 8);
        assertTrue(state.isRiding());
        assertTrue(state.shouldDefer());
        state.observePassengers(100, 7, 8);
        assertTrue(state.isRiding(), "unrelated vehicle updates must not clear the player's vehicle");
        state.observePassengers(101, 8);
        assertFalse(state.isRiding());
        assertFalse(state.shouldDefer());
    }

    @Test
    void onlyOwnPlayerMetadataCanSetSleepingOrGliding() {
        TransitionDeferralState state = new TransitionDeferralState();
        state.initialize(42);
        state.observePlayerMetadata(100, 6, "SLEEPING");
        state.observePlayerMetadata(100, 0, (byte) 0x80);
        assertFalse(state.shouldDefer());
        state.observePlayerMetadata(42, 6, "SLEEPING");
        assertTrue(state.isSleeping());
        assertTrue(state.shouldDefer());
        state.observePlayerMetadata(42, 6, "STANDING");
        assertFalse(state.isSleeping());
        state.observePlayerMetadata(42, 0, (byte) 0x80);
        assertTrue(state.isGliding());
        assertTrue(state.shouldDefer());
        state.observePlayerMetadata(42, 0, (byte) 0x00);
        assertFalse(state.isGliding());
        assertFalse(state.shouldDefer());
    }

    @Test
    void loginAndRespawnResetAllDeferredStates() {
        TransitionDeferralState state = new TransitionDeferralState();
        state.initialize(42);
        state.observeContainerOpened();
        state.observePassengers(101, 42);
        state.observePlayerMetadata(42, 6, "SLEEPING");
        state.observePlayerMetadata(42, 0, (byte) 0x80);
        assertTrue(state.shouldDefer());

        state.resetForRespawn();
        assertEquals(42, state.selfEntityId());
        assertFalse(state.shouldDefer());
        assertFalse(state.isContainerOpen());
        assertFalse(state.isRiding());
        assertFalse(state.isSleeping());
        assertFalse(state.isGliding());

        state.initialize(99);
        assertEquals(99, state.selfEntityId());
        assertFalse(state.shouldDefer());
    }
}
