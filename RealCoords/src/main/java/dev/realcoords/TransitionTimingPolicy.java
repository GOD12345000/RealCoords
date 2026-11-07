package dev.realcoords;

final class TransitionTimingPolicy {
    private final long cooldownNanos;
    private final long graceNanos;
    private long lastTransitionCompletedNanos = Long.MIN_VALUE;

    TransitionTimingPolicy(int cooldownTicks, int graceTicks) {
        cooldownNanos = Math.max(0, cooldownTicks) * 50_000_000L;
        graceNanos = Math.max(0, graceTicks) * 50_000_000L;
    }

    void transitionCompleted(long nowNanos) {
        lastTransitionCompletedNanos = nowNanos;
    }

    boolean cooldownActive(long nowNanos) {
        return lastTransitionCompletedNanos != Long.MIN_VALUE
                && nowNanos - lastTransitionCompletedNanos < cooldownNanos;
    }

    boolean graceActive(long nowNanos) {
        return lastTransitionCompletedNanos != Long.MIN_VALUE
                && nowNanos - lastTransitionCompletedNanos < graceNanos;
    }

    boolean safetyGuardRelaxed(boolean transitionActive, long nowNanos) {
        return transitionActive || graceActive(nowNanos);
    }

    double movementJumpLimit(boolean transitionActive, long nowNanos,
                             double maxServerboundJump, double transitionGraceMaxJump) {
        return safetyGuardRelaxed(transitionActive, nowNanos)
                ? transitionGraceMaxJump : maxServerboundJump;
    }
}
