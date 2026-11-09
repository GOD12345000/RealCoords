package dev.realcoords;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReoriginTransitionTest {
    @Test
    void followsTheExplicitTransitionLifecycle() {
        ReoriginTransition transition = new ReoriginTransition();

        assertEquals(ReoriginTransition.State.NORMAL, transition.state());
        transition.enter(ReoriginTransition.State.PREPARE);
        transition.enter(ReoriginTransition.State.RESET);
        transition.enter(ReoriginTransition.State.REPLAY);
        transition.enter(ReoriginTransition.State.DRAIN);
        transition.enter(ReoriginTransition.State.NORMAL);

        assertEquals(ReoriginTransition.State.NORMAL, transition.state());
        assertNull(transition.failure());
    }

    @Test
    void rejectsTransitionsThatSkipOrReverseAStage() {
        ReoriginTransition transition = new ReoriginTransition();

        assertThrows(IllegalStateException.class,
                () -> transition.enter(ReoriginTransition.State.REPLAY));
        transition.enter(ReoriginTransition.State.PREPARE);
        assertThrows(IllegalStateException.class,
                () -> transition.enter(ReoriginTransition.State.DRAIN));
        assertEquals(ReoriginTransition.State.PREPARE, transition.state());
    }

    @Test
    void recordsTheFailedStageAndReasonAndMakesFailureTerminal() {
        ReoriginTransition transition = new ReoriginTransition();
        transition.enter(ReoriginTransition.State.PREPARE);
        transition.enter(ReoriginTransition.State.RESET);

        transition.fail("synthetic confirmation timed out");

        assertEquals(ReoriginTransition.State.FAILED, transition.state());
        assertEquals(new ReoriginTransition.Failure(
                ReoriginTransition.State.RESET, "synthetic confirmation timed out"), transition.failure());
        assertThrows(IllegalStateException.class,
                () -> transition.enter(ReoriginTransition.State.REPLAY));
    }

    @Test
    void rejectsFailureWithoutAnActiveTransition() {
        ReoriginTransition transition = new ReoriginTransition();

        assertThrows(IllegalStateException.class, () -> transition.fail("not started"));
        assertEquals(ReoriginTransition.State.NORMAL, transition.state());
    }
}
