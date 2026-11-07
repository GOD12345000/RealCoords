package dev.realcoords;

/**
 * Explicit lifecycle for a continuous re-origin transaction.
 *
 * Instances are owned by the downstream channel event loop; callers must not
 * mutate one from another thread.
 */
final class ReoriginTransition {
    enum State {
        NORMAL,
        PREPARE,
        RESET,
        REPLAY,
        DRAIN,
        FAILED
    }

    record Failure(State failedAt, String reason) {
        Failure {
            if (failedAt == null || failedAt == State.FAILED) {
                throw new IllegalArgumentException("failedAt must identify the active stage");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("failure reason must not be blank");
            }
        }
    }

    private State state = State.NORMAL;
    private Failure failure;

    State state() {
        return state;
    }

    Failure failure() {
        return failure;
    }

    void enter(State next) {
        if (next == null) throw new IllegalArgumentException("next state must not be null");
        boolean allowed = switch (state) {
            case NORMAL -> next == State.PREPARE;
            case PREPARE -> next == State.RESET;
            case RESET -> next == State.REPLAY;
            case REPLAY -> next == State.DRAIN;
            case DRAIN -> next == State.NORMAL;
            case FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid re-origin transition: " + state + " -> " + next);
        }
        state = next;
    }

    void fail(String reason) {
        if (state == State.NORMAL) {
            throw new IllegalStateException("Cannot fail a transition that has not started");
        }
        if (state == State.FAILED) return;
        failure = new Failure(state, reason);
        state = State.FAILED;
    }
}
