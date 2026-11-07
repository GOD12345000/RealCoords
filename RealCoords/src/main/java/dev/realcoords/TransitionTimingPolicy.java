package dev.realcoords;

/** Monotonic per-session timing windows for live re-origin transitions. */
final class TransitionTimingPolicy {
    private final long cooldownNanos;
    private final long graceNanos;

    TransitionTimingPolicy(int cooldownTicks, int graceTicks) {
        cooldownNanos = Math.max(0, cooldownTicks) * 50_000_000L;
        graceNanos = Math.max(0, graceTicks) * 50_000_000L;
    }

    void transitionCompleted(long nowNanos) {
    }

    boolean cooldownActive(long nowNanos) {
        return false;
    }

    boolean graceActive(long nowNanos) {
        return false;
    }

    boolean safetyGuardRelaxed(boolean transitionActive, long nowNanos) {
        return transitionActive;
    }
}
