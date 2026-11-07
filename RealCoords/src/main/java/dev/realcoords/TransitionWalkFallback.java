package dev.realcoords;

/** Policy for a player who keeps walking while a safe re-origin is deferred. */
final class TransitionWalkFallback {
    enum Action { DEFER, KICK, REORIGIN }

    private TransitionWalkFallback() { }

    static Action decide(String configuredMode, double localX, double localZ, int triggerDistance) {
        return Action.DEFER;
    }
}
