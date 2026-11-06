package dev.realcoords;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShiftConfigTransitionTest {
    @Test
    void missingTransitionOptionsUseSafeDefaultsForExistingConfigs() {
        ShiftConfig config = ShiftConfig.parse(Map.of("origin-shift", Map.of("shift-while-moving", false)), ignored -> {});

        assertEquals(100, config.transitionTimeoutTicks);
        assertEquals(600, config.transitionCooldownTicks);
        assertEquals(20, config.transitionGraceTicks);
        assertEquals("none", config.walkFallback);
        assertFalse(config.shiftWhileMoving);
    }

    @Test
    void transitionOptionsLoadConfiguredValues() {
        Map<String, Object> originShift = new HashMap<>();
        originShift.put("transition-timeout-ticks", 240);
        originShift.put("transition-cooldown-ticks", 900);
        originShift.put("transition-grace-ticks", 35);
        originShift.put("walk-fallback", "ReOrigin");

        ShiftConfig config = ShiftConfig.parse(Map.of("origin-shift", originShift), ignored -> {});

        assertEquals(240, config.transitionTimeoutTicks);
        assertEquals(900, config.transitionCooldownTicks);
        assertEquals(35, config.transitionGraceTicks);
        assertEquals("reorigin", config.walkFallback);
    }

    @Test
    void invalidFallbackUsesNoneAndEmitsWarning() {
        AtomicInteger warnings = new AtomicInteger();
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of("walk-fallback", "teleport")),
                ignored -> warnings.incrementAndGet());

        assertEquals("none", config.walkFallback);
        assertEquals(1, warnings.get());
    }

    @Test
    void transitionTickValuesAreClampedToValidRanges() {
        ShiftConfig config = ShiftConfig.parse(
                Map.of("origin-shift", Map.of(
                        "transition-timeout-ticks", 0,
                        "transition-cooldown-ticks", -1,
                        "transition-grace-ticks", -1)),
                ignored -> {});

        assertEquals(1, config.transitionTimeoutTicks);
        assertEquals(0, config.transitionCooldownTicks);
        assertEquals(0, config.transitionGraceTicks);
    }
}
