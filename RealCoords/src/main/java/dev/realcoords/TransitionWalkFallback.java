package dev.realcoords;

final class TransitionWalkFallback {
    enum Action { DEFER, KICK, REORIGIN }

    private TransitionWalkFallback() { }

    static Action decide(String configuredMode, double localX, double localZ, int triggerDistance) {
        if (triggerDistance <= 0 || !Double.isFinite(localX) || !Double.isFinite(localZ)) {
            return Action.DEFER;
        }
        double threshold = triggerDistance * 8.0;
        if (Math.max(Math.abs(localX), Math.abs(localZ)) <= threshold) return Action.DEFER;
        if ("kick".equalsIgnoreCase(configuredMode)) return Action.KICK;
        if ("reorigin".equalsIgnoreCase(configuredMode)) return Action.REORIGIN;
        return Action.DEFER;
    }
}
