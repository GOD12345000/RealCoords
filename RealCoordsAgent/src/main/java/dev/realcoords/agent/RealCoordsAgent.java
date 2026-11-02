package dev.realcoords.agent;

import java.lang.instrument.Instrumentation;

/**
 * Installs the narrowly scoped Geyser 2.12.1 downstream listener-order hook.
 */
public final class RealCoordsAgent {
    private RealCoordsAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new GeyserSessionTransformer(), false);
        System.err.println("[RealCoordsAgent] Installed the Geyser downstream listener-order transformer.");
    }
}
